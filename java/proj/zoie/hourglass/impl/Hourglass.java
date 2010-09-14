package proj.zoie.hourglass.impl;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriter.MaxFieldLength;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.SimpleFSDirectory;

import proj.zoie.api.DataConsumer;
import proj.zoie.api.DirectoryManager;
import proj.zoie.api.IndexReaderFactory;
import proj.zoie.api.ZoieException;
import proj.zoie.api.ZoieIndexReader;
import proj.zoie.api.ZoieMultiReader;
import proj.zoie.api.impl.util.FileUtil;
import proj.zoie.api.indexing.IndexReaderDecorator;
import proj.zoie.api.indexing.ZoieIndexableInterpreter;
import proj.zoie.impl.indexing.ZoieConfig;
import proj.zoie.impl.indexing.ZoieSystem;
import proj.zoie.impl.indexing.internal.IndexSignature;
import proj.zoie.impl.indexing.internal.ZoieIndexDeletionPolicy;

public class Hourglass<R extends IndexReader, V> implements IndexReaderFactory<ZoieIndexReader<R>>, DataConsumer<V>
{
  public static final Logger log = Logger.getLogger(Hourglass.class);
  private final HourglassDirectoryManagerFactory _dirMgrFactory;
  private final ZoieIndexableInterpreter<V> _interpreter;
  private final IndexReaderDecorator<R> _decorator;
  private final ZoieConfig _zConfig;
  private volatile ZoieSystem<R, V> _currentZoie;
  private volatile boolean _isShutdown = false;
  private final ReentrantReadWriteLock _shutdownLock = new ReentrantReadWriteLock();
  private final ReentrantLock _consumeLock = new ReentrantLock();
  private final ReaderManager<R, V> _readerMgr;
  private volatile long _currentVersion = 0L;
  private long _freshness = 1000;
  private final HourGlassScheduler _scheduler;
  public Hourglass(HourglassDirectoryManagerFactory dirMgrFactory, ZoieIndexableInterpreter<V> interpreter, IndexReaderDecorator<R> readerDecorator,ZoieConfig zoieConfig)
  {
    _zConfig = zoieConfig;
    _dirMgrFactory = dirMgrFactory;
    _scheduler = _dirMgrFactory.getScheduler();
    _dirMgrFactory.clearRecentlyChanged();
    _interpreter = interpreter;
    _decorator = readerDecorator;
    _readerMgr = new ReaderManager<R, V>(this, _dirMgrFactory, _decorator, loadArchives());
    _currentVersion = _dirMgrFactory.getArchivedVersion();
    _currentZoie = _readerMgr.retireAndNew(null);
    _currentZoie.start();
    log.info("start Hourglass at version: " + _currentVersion);
  }
  protected List<ZoieIndexReader<R>> loadArchives()
  {
    List<ZoieIndexReader<R>> archives = new ArrayList<ZoieIndexReader<R>>();
    long t0 = System.currentTimeMillis();
    List<Directory> dirs = _dirMgrFactory.getAllArchivedDirectories();
    for(Directory dir : dirs)
    {
      IndexReader reader;
      try
      {
        reader = IndexReader.open(dir,true);
        ZoieMultiReader<R> zoiereader = new ZoieMultiReader<R>(reader, _decorator);
        archives.add(zoiereader);
      } catch (CorruptIndexException e)
      {
        log.error("corruptedIndex", e);
      } catch (IOException e)
      {
        log.error("IOException", e);
      }
    }
    log.info("load "+dirs.size()+" archived indices of " + getSizeBytes() +" bytes in " + (System.currentTimeMillis() - t0) + "ms");
    return archives;
  }
  private ZoieSystem<R, V> createZoie(DirectoryManager dirmgr)
  {
    return new ZoieSystem<R, V>(dirmgr, _interpreter, _decorator, _zConfig);
  }

  public ZoieConfig getzConfig()
  {
    return _zConfig;
  }
  public ZoieSystem<R, V> getCurrentZoie()
  {
    return _currentZoie;
  }
  public HourglassDirectoryManagerFactory getDirMgrFactory()
  {
    return _dirMgrFactory;
  }
  /* (non-Javadoc)
   * @see proj.zoie.api.IndexReaderFactory#getAnalyzer()
   */
  public Analyzer getAnalyzer()
  {
    return _zConfig.getAnalyzer();
  }

  /**
   * return a list of ZoieIndexReaders. These readers are reference counted and this method
   * should be used in pair with returnIndexReaders(List<ZoieIndexReader<R>> readers) {@link #returnIndexReaders(List)}.
   * It is typical that we create a MultiReader from these readers. When creating MultiReader, it should be created with
   * the closeSubReaders parameter set to false in order to do reference counting correctly.
   * <br> If this indexing system is already shut down, then we return an empty list.
   * @see proj.zoie.hourglass.impl.Hourglass#returnIndexReaders(List)
   * @see proj.zoie.api.IndexReaderFactory#getIndexReaders()
   */
  public List<ZoieIndexReader<R>> getIndexReaders() throws IOException
  {
    long t0 = System.currentTimeMillis();
    try
    {
      _shutdownLock.readLock().lock();
      if (_isShutdown)
      {
        log.warn("System already shut down. No search request allowed.");
        List<ZoieIndexReader<R>> list = new ArrayList<ZoieIndexReader<R>>();
        return list;// if already shutdown, return an empty list
      }
      try
      {
        cacheLock.lock();
        if (System.currentTimeMillis() - lastupdate > _freshness)
        {
          updateCachedReaders();
        }
        List<ZoieIndexReader<R>> rlist = list;
        for(ZoieIndexReader<R> r : rlist)
        {
          r.incRef();
        }
        return rlist;
      } finally
      {
        cacheLock.unlock();
      }
    }
    finally
    {
      _shutdownLock.readLock().unlock();
    }
  }
  /**
   * not thread safe. should be properly lock. Right now we have two places to use it
   * and locked by the shutdown lock. If it gets more complicated, we should use separate
   * lock.
   * @throws IOException
   */
  private void updateCachedReaders() throws IOException
  {
    List<ZoieIndexReader<R>> olist = list;
    returnIndexReaders(olist);
    list = _readerMgr.getIndexReaders();
    lastupdate = System.currentTimeMillis();
  }
  /**
   * not thread safe. should be properly lock. Right now we have two places to use it
   * and locked by the shutdown lock. If it gets more complicated, we should use separate
   * lock.
   */
  private void clearCachedReaders()
  {
    List<ZoieIndexReader<R>> olist = list;
    returnIndexReaders(olist);
    list = null;
    lastupdate = 0;
  }
  private volatile long lastupdate=0;
  private  volatile   List<ZoieIndexReader<R>> list = new ArrayList<ZoieIndexReader<R>>();
  private final ReentrantLock cacheLock = new ReentrantLock();

  /* (non-Javadoc)
   * @see proj.zoie.api.IndexReaderFactory#returnIndexReaders(java.util.List)
   */
  public void returnIndexReaders(List<ZoieIndexReader<R>> readers)
  {
    _currentZoie.returnIndexReaders(readers);
  }

  /* (non-Javadoc)
   * @see proj.zoie.api.DataConsumer#consume(java.util.Collection)
   */
  public void consume(Collection<DataEvent<V>> data) throws ZoieException
  {
    try
    {
      _consumeLock.lock(); // one at a time so we don't mess up during forward rolling.
                           // also we cannot do two consumptions at the same time anyway.
      try
      {
        _shutdownLock.readLock().lock();
        if (data == null || data.size() == 0) return;
        if (_isShutdown)
        {
          log.warn("System already shut down. Rejects indexing request.");
          return; // if the system is already shut down, we don't do anything.
        }
        // need to check time boundary. When we hit boundary, we need to trigger DM to 
        // use new dir for zoie and the old one will be archive.
        if (!_dirMgrFactory.updateDirectoryManager())
        {
          _currentZoie.consume(data);
        } else
        {
          // new time period
          _currentZoie = _readerMgr.retireAndNew(_currentZoie);
          _currentZoie.start();
          _currentZoie.consume(data);
        }
      } finally
      {
        _shutdownLock.readLock().unlock();
      }
    } finally
    {
      _consumeLock.unlock();
    }
  }

  public void shutdown()
  {
    try
    {
      _shutdownLock.writeLock().lock();
      if (_isShutdown)
      {
        log.info("system already shut down");
        return;
      }
      _isShutdown = true;
    } finally
    {
      _shutdownLock.writeLock().unlock();
    }
    clearCachedReaders();
    _readerMgr.shutdown();
    log.info("shut down complete.");
  }

  /* (non-Javadoc)
   * @see proj.zoie.api.DataConsumer#getVersion()
   */
  public long getVersion()
  {
    _currentVersion = Math.max(_currentVersion, _currentZoie.getCurrentVersion());
    return _currentVersion;
  }
  public static class ReaderManager<R extends IndexReader, V>
  {
    private final HourglassDirectoryManagerFactory _dirMgrFactory;
    private final Hourglass<R, V> hg;
    private final IndexReaderDecorator<R> _decorator;
    private volatile Box<R, V> box;
    private volatile boolean isShutdown = false;
    private ExecutorService threadPool = Executors.newCachedThreadPool();
    public ReaderManager(final Hourglass<R, V> hourglass, HourglassDirectoryManagerFactory dirMgrFactory,
        IndexReaderDecorator<R> decorator,
        List<ZoieIndexReader<R>> initArchives)
    {
      hg = hourglass;
      _dirMgrFactory = dirMgrFactory;
      _decorator = decorator;
      box = new Box<R, V>(initArchives, Collections.EMPTY_LIST, Collections.EMPTY_LIST, _decorator);
      threadPool.execute(new Runnable(){
        final int trimThreshold = hourglass._scheduler.getTrimThreshold();

        @Override
        public void run()
        {
          while(true)
          {
            try
            {
              Thread.sleep(60000);
            } catch (InterruptedException e)
            {
              log.warn(e);
            }
            List<ZoieIndexReader<R>> archives = new LinkedList<ZoieIndexReader<R>>(box._archives);
            List<ZoieIndexReader<R>> add = new LinkedList<ZoieIndexReader<R>>();
            try
            {
              hourglass._shutdownLock.readLock().lock();
              if (isShutdown)
              {
                log.info("Already shut down. Quiting maintenance thread.");
                break;
              }
              if (archives.size() > trimThreshold)
              { 
                log.info("to maintain");
              } else continue;
//              consolidate(archives, add);
              trim(archives);
              // swap the archive with consolidated one
              swapArchives(archives, add);
            } finally
            {
              hourglass._shutdownLock.readLock().unlock();
            }
          }
        }});
    }
    /**
     * consolidate the archived Index to one big optimized index and put in add
     * @param toRemove
     * @param add
     */
    private void trim(List<ZoieIndexReader<R>> toRemove)
    {
      long timenow = System.currentTimeMillis();
      List<ZoieIndexReader<R>> toKeep = new LinkedList<ZoieIndexReader<R>>();
      Calendar now = Calendar.getInstance();
      now.setTimeInMillis(timenow);
      Calendar threshold = hg._scheduler.getTrimTime(now);
      for(int i=0; i<toRemove.size(); i++)
      {
    	  NIOFSDirectory  dir = (NIOFSDirectory) toRemove.get(i).directory();
        String path = dir.getFile().getName();
        Calendar archivetime = null;
        try
        {
          archivetime = HourglassDirectoryManagerFactory.getCalendarTime(path);
        } catch (ParseException e)
        {
          log.error("index directory name bad. potential corruption. Move on without trimming.", e);
          toKeep.add(toRemove.get(i));
          continue;
        }
        if (archivetime.before(threshold))
        {
          log.info("trimming: remove " + path);
        } else
        {
          toKeep.add(toRemove.get(i));
        }
      }
      toRemove.removeAll(toKeep);
    }
    /**
     * consolidate the archived Index to one big optimized index and put in add
     * @param archived
     * @param add
     */
    private void consolidate(List<ZoieIndexReader<R>> archived, List<ZoieIndexReader<R>> add)
    {
      log.info("begin consolidate ... ");
      long b4 = System.currentTimeMillis();
      SimpleFSDirectory target = (SimpleFSDirectory) archived.get(0).directory();
      log.info("into: "+target.getFile().getAbsolutePath());
      SimpleFSDirectory sources[] = new SimpleFSDirectory[archived.size()-1];
      IndexSignature sigs[] = new IndexSignature[archived.size()];
      sigs[0] = _dirMgrFactory.getIndexSignature(target.getFile()); // the target index signature
      log.info("target version: " + sigs[0].getVersion());
      for(int i=1; i<archived.size(); i++)
      {
        sources[i-1] = (SimpleFSDirectory) archived.get(i).directory();
        sigs[i] = _dirMgrFactory.getIndexSignature(sources[i-1].getFile());  // get other index signatures
        log.info("from: " + sources[i-1].getFile().getAbsolutePath());
      }
      IndexWriter idxWriter = null;
      try
      {
        idxWriter = new IndexWriter(target, null, false, new ZoieIndexDeletionPolicy(), MaxFieldLength.UNLIMITED);
        idxWriter.addIndexesNoOptimize(sources);
        idxWriter.optimize(1);
      } catch (CorruptIndexException e)
      {
        log.error("index currupted during consolidation", e);
      } catch (LockObtainFailedException e)
      {
        log.error("LockObtainFailedException during consolidation", e);
      } catch (IOException e)
      {
        log.error("IOException during consolidation", e);
      } finally
      {
        if (idxWriter != null)
        {
          try
          {
            idxWriter.close();
            // remove the originals from disk
            for(SimpleFSDirectory dir : sources)
            {
              IndexSignature sig = _dirMgrFactory.getIndexSignature(dir.getFile());
              log.info(dir.getFile() + "---" + (dir.getFile().exists()?" not deleted ":" deleted") + " version: " + sig.getVersion());
              FileUtil.rmDir(dir.getFile());
              log.info(dir.getFile() + "---" + (dir.getFile().exists()?" not deleted ":" deleted"));
            }
            long tgtversion = 0;
            for(int i = sigs.length - 1; i >= 0; i--)
            { // get the largest version so far
              if (sigs[i].getVersion() > tgtversion) tgtversion = sigs[i].getVersion();
            }
            // save the version to target
            IndexSignature tgtsig = _dirMgrFactory.getIndexSignature(target.getFile());
            tgtsig.updateVersion(tgtversion);
            _dirMgrFactory.saveIndexSignature(target.getFile(), tgtsig);
            log.info("saveIndexSignature to " + target.getFile().getAbsolutePath() + " at version: " + tgtsig.getVersion());
            // open index reader for the consolidated index
            IndexReader reader = IndexReader.open(target, true);
            // decorate the index
            ZoieMultiReader<R> zoiereader = new ZoieMultiReader<R>(reader, _decorator);
            add.add(zoiereader);
            long b5 = System.currentTimeMillis();
            log.info("done consolidate in " + (System.currentTimeMillis() - b4)+"ms  blocked for " + (System.currentTimeMillis()-b5));
          } catch (CorruptIndexException e)
          {
            log.error("index currupted during consolidation", e);
          } catch (IOException e)
          {
            log.error("IOException during consolidation", e);
          }
        }
      }
    }
    /**
     * The readers removed will also be decRef(). But the readers to be added will NOT get incRef(),
     * which means we assume the newly added ones have already been incRef().
     * remove and add should be <b>disjoint</b>
     * @param remove the readers to be remove. This has to be disjoint from add.
     * @param add
     */
    public synchronized void swapArchives(List<ZoieIndexReader<R>> remove, List<ZoieIndexReader<R>> add)
    {
      List<ZoieIndexReader<R>> archives = new LinkedList<ZoieIndexReader<R>>(add);
      if (!box._archives.containsAll(remove))
      {
        log.error("swapArchives: potential sync issue. ");
      }
      archives.addAll(box._archives);
      archives.removeAll(remove);
      for(ZoieIndexReader<R> r : remove)
      {
        try
        {
          r.decRef();
          if (log.isDebugEnabled())
          {
            log.debug("remove time " + r.directory() + " refCount: " + r.getRefCount());
          }
        } catch (IOException e)
        {
          log.error("IOException during swapArchives", e);
        }
      }
      Box<R, V> newbox = new Box<R, V>(archives, box._retiree, box._actives, _decorator);
      box = newbox;
    }
    public synchronized ZoieSystem<R, V> retireAndNew(final ZoieSystem<R, V> old)
    {
      DirectoryManager _dirMgr = _dirMgrFactory.getDirectoryManager();
      _dirMgrFactory.clearRecentlyChanged();
      ZoieSystem<R, V> newzoie = hg.createZoie(_dirMgr);
      List<ZoieSystem<R, V>> actives = new LinkedList<ZoieSystem<R, V>>(box._actives);
      List<ZoieSystem<R, V>> retiring = new LinkedList<ZoieSystem<R, V>>(box._retiree);
      if (old!=null)
      {
        actives.remove(old);
        retiring.add(old);
        threadPool.execute(new Runnable()
        {
          @Override
          public void run()
          {
            retire(old);
          }});
      }
      actives.add(newzoie);
      Box<R, V> newbox = new Box<R, V>(box._archives, retiring, actives, _decorator);
      box = newbox;
      return newzoie;
    }
    /**
     * @param zoie
     * @param reader the IndexReader opened on the index the give zoie had written to.
     */
    public synchronized void archive(ZoieSystem<R, V> zoie, IndexReader reader)
    {
      List<ZoieIndexReader<R>> _archives = new LinkedList<ZoieIndexReader<R>>(box._archives);
      List<ZoieSystem<R, V>> actives = new LinkedList<ZoieSystem<R, V>>(box._actives);
      List<ZoieSystem<R, V>> retiring = new LinkedList<ZoieSystem<R, V>>(box._retiree);
      retiring.remove(zoie);
      if (reader != null)
      {
        try
        {
          ZoieMultiReader<R> zoiereader = new ZoieMultiReader<R>(reader, _decorator);
          _archives.add(zoiereader);
        } catch (IOException e)
        {
          log.error(e);
        }
      }
      Box<R, V> newbox = new Box<R, V>(_archives, retiring, actives, _decorator);
      box = newbox;
    }
    private synchronized void preshutdown()
    {
      log.info("shutting down thread pool.");
      threadPool.shutdown();
      isShutdown = true;
    }
    public void shutdown()
    {
      preshutdown();
      while(true)
      {
        TimeUnit unit=TimeUnit.SECONDS;
        long t=10L;
        try
        {
          if (threadPool.awaitTermination(t, unit)) break;
        } catch (InterruptedException e)
        {
          log.warn("Exception when trying to shutdown. Will retry.", e);
        }
      }
      log.info("shutting down thread pool complete.");
      log.info("shutting down indices.");
      box.shutdown();
      log.info("shutting down indices complete.");
    }
    public synchronized List<ZoieIndexReader<R>> getIndexReaders() throws IOException
    {
      List<ZoieIndexReader<R>> list = new ArrayList<ZoieIndexReader<R>>();
      // add the archived index readers
      for(ZoieIndexReader<R> r : box._archives)
      {
        r.incRef();
        list.add(r);
      }
      // add the retiring index readers
      for(ZoieSystem<R, V> zoie : box._retiree)
      {
        list.addAll(zoie.getIndexReaders());
      }
      // add the active index readers
      for(ZoieSystem<R, V> zoie : box._actives)
      {
        list.addAll(zoie.getIndexReaders());
      }
      return list;
    }  
    protected void retire(ZoieSystem<R, V> zoie)
    {
      long t0 = System.currentTimeMillis();
      log.info("retiring " + zoie.getAdminMBean().getIndexDir());
      while(true)
      {
        long flushwait = 200000L;
        try
        {
          zoie.flushEvents(flushwait);
          zoie.getAdminMBean().setUseCompoundFile(true);
          zoie.getAdminMBean().optimize(1);
          break;
        } catch (IOException e)
        {
          log.error("retiring " + zoie.getAdminMBean().getIndexDir() + " Should investigate. But move on now.", e);
          break;
        } catch (ZoieException e)
        {
          if (e.getMessage().indexOf("timed out")<0)
          {
            break;
          } else
          {
            log.info("retiring " + zoie.getAdminMBean().getIndexDir() + " flushing processing " + flushwait +"ms elapsed");
          }
        }
      }
      IndexReader reader = null;
      try
      {
        reader = getArchive(zoie);
      } catch (CorruptIndexException e)
      {
        log.error("retiring " + zoie.getAdminMBean().getIndexDir() + " Should investigate. But move on now.", e);
      } catch (IOException e)
      {
        log.error("retiring " + zoie.getAdminMBean().getIndexDir() + " Should investigate. But move on now.", e);
      }
      archive(zoie, reader);
      log.info("retired " + zoie.getAdminMBean().getIndexDir() + " in " + (System.currentTimeMillis()-t0)+"ms");
      log.info("Disk Index Size Total Now: " + (hg.getSizeBytes()/1024L) + "KB");
      zoie.shutdown();
    }
    private IndexReader getArchive(ZoieSystem<R, V> zoie) throws CorruptIndexException, IOException
    {
      String dirName = zoie.getAdminMBean().getIndexDir();
      Directory dir = new SimpleFSDirectory(new File(dirName));
      IndexReader reader = null;
      if (IndexReader.indexExists(dir))
      {
        reader  = IndexReader.open(dir, true);
      }
      else
      {
        log.info("empty index " + dirName);
        reader = null;
      }
      return reader;
    }
  }
  public static class Box<R extends IndexReader, V>
  {
    List<ZoieIndexReader<R>> _archives;
    List<ZoieSystem<R, V>> _retiree;
    List<ZoieSystem<R, V>> _actives;
    IndexReaderDecorator<R> _decorator;
    /**
     * Copy the given lists to have immutable behavior.
     * @param archives
     * @param retiree
     * @param actives
     * @param decorator
     */
    public Box(List<ZoieIndexReader<R>> archives, List<ZoieSystem<R, V>> retiree, List<ZoieSystem<R, V>> actives, IndexReaderDecorator<R> decorator)
    {
      _archives = new LinkedList<ZoieIndexReader<R>>(archives);
      _retiree = new LinkedList<ZoieSystem<R, V>>(retiree);
      _actives = new LinkedList<ZoieSystem<R, V>>(actives);
      _decorator = decorator;
      if (log.isDebugEnabled())
      {
        for(ZoieIndexReader<R> r : _archives)
        {
          log.debug("archive " + r.directory() + " refCount: " + r.getRefCount());
        }
      }
    }
    public void shutdown()
    {
      for(ZoieIndexReader<R> r : _archives)
      {
        try
        {
          r.decRef();
        } catch (IOException e)
        {
          log.error("error decRef during shutdown", e);
        }
        log.info("refCount at shutdown: " + r.getRefCount() + " " + r.directory());
      }
      for(ZoieSystem<R, V> zoie : _retiree)
      {
        zoie.shutdown();
      }
      // add the active index readers
      for(ZoieSystem<R, V> zoie : _actives)
      {
        while(true)
        {
          long flushwait = 200000L;
          try
          {
            zoie.flushEvents(flushwait);
            zoie.getAdminMBean().setUseCompoundFile(true);
            zoie.getAdminMBean().optimize(1);
            break;
          } catch (IOException e)
          {
            log.error("pre-shutdown optimization " + zoie.getAdminMBean().getIndexDir() + " Should investigate. But move on now.", e);
            break;
          } catch (ZoieException e)
          {
            if (e.getMessage().indexOf("timed out")<0)
            {
              break;
            } else
            {
              log.info("pre-shutdown optimization " + zoie.getAdminMBean().getIndexDir() + " flushing processing " + flushwait +"ms elapsed");
            }
          }
        }
        zoie.shutdown();
      }
    }
  }
  public long getSizeBytes()
  {
    return _dirMgrFactory.getDiskIndexSizeBytes();
  }
}
