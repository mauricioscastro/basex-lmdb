package org.basex.query;

import lmdb.db.JdbcDataManager;
import org.apache.commons.io.FilenameUtils;
import org.basex.build.MemBuilder;
import org.basex.build.xml.XMLParser;
import org.basex.core.MainOptions;
import org.basex.data.Data;
import org.basex.io.IO;
import org.basex.io.IOStream;
import org.basex.query.up.Updates;
import org.basex.query.util.list.ItemList;
import org.basex.query.util.pkg.ModuleLoader;
import org.basex.query.value.Value;
import org.basex.query.value.item.QNm;
import org.basex.query.value.node.ANode;
import org.basex.query.value.node.DBNode;
import org.basex.query.value.node.FElem;
import org.basex.util.InputInfo;
import org.basex.util.QueryInput;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.jdbc.support.rowset.SqlRowSetMetaData;

import javax.sql.DataSource;
import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import static org.basex.query.QueryError.BASX_GENERIC_X;
import static org.basex.query.QueryError.NODEFCOLL;
import static org.basex.query.QueryError.WHICHRES_X;

/**
 * This class provides access to all kinds of resources (databases, documents, database connections,
 * sessions) used by an XQuery expression.
 *
 * @author BaseX Team 2005-15, BSD License
 * @author Christian Gruen
 */
public final class QueryResources {
  /** Textual resources. */
  public final HashMap<String, String[]> texts = new HashMap<>();

  /** Database context. */
  private final QueryContext qc;

  /** Collections: single nodes and sequences. */
  private final ArrayList<Value> colls = new ArrayList<>(1);
  /** Names of collections. */
  private final ArrayList<String> collNames = new ArrayList<>(1);
  /** Opened databases. */
  private final ArrayList<Data> datas = new ArrayList<>(1);
  private ArrayList<String> docs = new ArrayList<String>();
  private ArrayList<Data> data = new ArrayList<Data>();
  /** Indicates if the first database in the context is globally opened. */
  private boolean globalData;

  /** Module loader. */
  private ModuleLoader modules;
  /** External resources. */
  private final HashMap<Class<? extends QueryResource>, QueryResource> external = new HashMap<>();

  /** Pending output. */
  public final ItemList output = new ItemList();
  /** Pending updates. */
  Updates updates;

  /**
   * Constructor.
   * @param qc query context
   */
  QueryResources(final QueryContext qc) {
    this.qc = qc;
  }

//  /**
//   * Compiles the resources.
//   * @param nodes input node set
//   * @return context value
//   */
//  Value compile(final DBNodes nodes) {
//    // assign initial context value
//    final Data data = nodes.data();
//    final boolean all = nodes.all();
//    final Value value = DBNodeSeq.get(new IntList(nodes.pres()), data, all, all);
//
//    // create default collection: use initial node set if it contains all
//    // documents of the database. otherwise, create new node set
//    final Value coll = all ? value : DBNodeSeq.get(data.resources.docs(), data, true, true);
//    addCollection(coll, data.meta.name);
//    addData(data);
//    synchronized(qc.context.datas) { qc.context.datas.pin(data); }
//
//    globalData = true;
//    return value;
//  }

  /**
   * Adds an external resource.
   * @param ext external resource
   */
  public void add(final QueryResource ext) {
    external.put(ext.getClass(), ext);
  }

  /**
   * Returns an external resource of the specified class.
   * @param <R> resource
   * @param resource external resource
   * @return resource
   */
  @SuppressWarnings("unchecked")
  public <R extends QueryResource> R get(final Class<? extends R> resource) {
    return (R) external.get(resource);
  }

  /**
   * Closes all opened data references that have not been added by the global context.
   */
  void close() {
    for(Data d: data) d.close();
//    for(final Data data : datas) Close.close(data, qc.context);
//    datas.clear();
    // close dynamically loaded JAR files
    if(modules != null) modules.close();
    // close external resources
    for(final QueryResource c : external.values()) c.close();
  }

  /**
   * Opens a new database or returns a reference to an already opened database.
   * @param name name of database
   * @param info input info
   * @return database instance
   * @throws QueryException query exception
   */
  public Data database(final String name, final InputInfo info) throws QueryException {

      try {
          Data d = null; //DiskDataManager.open(name, qc.options); // TODO: basex-lmdb: review
          if(d != null) {
              docs.add(name);
              return d;
          }
          throw new QueryException("document " + name + " not found");
      } catch (Exception e) {
          throw new QueryException(e);
      }

    // check if a database with the same name has already been opened
//    for(final Data data : datas) {
//      if(data.inMemory()) continue;
//      final String n = data.meta.name;
//      if(Prop.CASE ? n.equals(name) : n.equalsIgnoreCase(name)) return data;
//    }
//    try {
//      // open and add new data reference
//      final Context ctx = qc.context;
//      return addData(Open.open(name, ctx, ctx.options));
//    } catch(final IOException ex) {
//      throw BXDB_OPEN_X.get(info, ex);
//    }
  }

  /**
   * Evaluates {@code fn:doc()}: opens an existing database document, or creates a new
   * database and node.
//   * @param qi query input
//   * @param baseIO base URI
   * @param info input info
   * @return document
   * @throws QueryException query exception
   */
  public ANode doc(final String input, final InputInfo info)
      throws QueryException {
      try {
          String docURI = input.trim();
          if(!docURI.contains("://")) docURI = "bxk://" + docURI;
          ANode doc = resolveURI(docURI);
          if(doc != null) return doc;
      } catch(Exception e) {
          throw new QueryException(null, BASX_GENERIC_X, "document " + input + " not found", e);
      }
      throw new QueryException("document " + input + " not found");

      // ORIGINAL BASEX
//    // favor default database
//    final Data gd = globalData();
//    if(gd != null && qc.context.options.get(MainOptions.DEFAULTDB)) {
//      final int pre = gd.resources.doc(qi.original);
//      if(pre != -1) return new DBNode(gd, pre, Data.DOC);
//    }
//
//    // check currently opened databases
//    for(final Data data : datas) {
//      // check if database has a single document with an identical input path
//      if(data.meta.ndocs == 1 && IO.get(data.meta.original).eq(qi.input))
//        return new DBNode(data, 0, Data.DOC);
//
//      // check if database and input have identical name
//      // database instance has same name as input path
//      final String n = data.meta.name;
//      if(Prop.CASE ? n.equals(qi.db) : n.equalsIgnoreCase(qi.db)) return doc(data, qi, info);
//    }
//
//    // open new database, or create new instance
//    Data dt = open(qi);
//    if(dt == null) dt = create(qi, true, baseIO, info);
//    return doc(dt, qi, info);
  }


    private ANode resolveURI(String uri) throws IOException {

        // TODO: basex-lmdb: review

//        if (uri.startsWith("bxk://")) {
//            if(!DiskDataManager.isRunning()) throw new IOException("DiskDataManager must be configured and running for bxk:// uri to work correctly");
//            String docURI = uri.substring(6);
//            DBNode d = DiskDataManager.openDocument(docURI, qc.options);
//            if(d != null) {
//                //docs.add(docURI);
//                data.add(d.data);
//                return d;
//            }
//        }

        if (uri.startsWith("file://")) {
            File d = new File(FilenameUtils.normalize(qc.options.get(MainOptions.XMLPATH) + "/" + uri.substring(7)));
            if (!d.exists()) throw new FileNotFoundException(uri);
            return new DBNode(MemBuilder.build(uri, new XMLParser(new IOStream(new FileInputStream(d)), qc.options)));
        }

        if (uri.startsWith("jdbc://")) {
            if(JdbcDataManager.datasource.isEmpty()) throw new IOException("JdbcDataManager must be configured for jdbc:// uri to work correctly");
            int si = uri.indexOf('/', 8);
            String dsName = uri.substring(8, si);
            String sql = uri.substring(si + 1);
            DataSource ds = JdbcDataManager.datasource.get(dsName);
            if(ds == null) throw new IOException("can't find datasource=" + dsName + " from uri: " + uri);
            JdbcTemplate jdbct = new JdbcTemplate(ds);
            SqlRowSet sqlrs = jdbct.queryForRowSet(sql);
            SqlRowSetMetaData sqlrmd = sqlrs.getMetaData();
            FElem rdbmsDoc = new FElem(new QNm("ResultSet"));
            while (sqlrs.next()) {
                FElem row = new FElem(new QNm("Row"));
                for (int i = 1; i <= sqlrmd.getColumnCount(); i++) {
                    FElem col = new FElem(new QNm(sqlrmd.getColumnLabel(i)));
                    Object obj = sqlrs.getObject(i);
                    if (obj == null) continue;
                    if (obj.getClass().getName().contains("javax.sql.rowset.serial.")) {
                        try {
                            if (obj.getClass().getName().contains("Blob"))
                                obj = new String(((SerialBlob) obj).getBytes(1L, (int) ((SerialBlob) obj).length()));
                            if (obj.getClass().getName().contains("Clob"))
                                obj = ((SerialClob) obj).getSubString(1L, (int) ((SerialClob) obj).length());
                        } catch(Exception e) {
                            continue;
                        }
                    }
                    col.add(obj.toString().getBytes());
                    row.add(col);
                }
                rdbmsDoc.add(row);
            }
            return rdbmsDoc;
        }

        return null;
    }


  /**
   * Returns the default collection.
   * @param info input info
   * @return collection
   * @throws QueryException query exception
   */
  public Value collection(final InputInfo info) throws QueryException {
    if(colls.isEmpty()) throw NODEFCOLL.get(info);
    return colls.get(0);
  }

  /**
   * Evaluates {@code fn:collection()}: opens an existing database collection, or creates
   * a new data reference.
   * @param qi query input
   * @param baseIO base URI
   * @param info input info
   * @return collection
   * @throws QueryException query exception
   */
  public Value collection(final QueryInput qi, final IO baseIO, final InputInfo info)
      throws QueryException {

      return null; // TODO: basex-lmdb: review

//      List col = null;
//      try {
//          String docURI = qi.original.trim();
//          if(docURI.startsWith("bxk://")) docURI = docURI.substring(6);
//          col = DiskDataManager.openCollection(docURI);
//      } catch (IOException e) {
//          throw new QueryException(e);
//      }
//      docs.addAll(col);
//      return docs.isEmpty() ? Empty.SEQ : new LazyDBNodeSeq(col, qc.options); //, true);

      // ORIGINAL BASEX
//    // favor default database
//    final Data gd = globalData();
//    if(qc.context.options.get(MainOptions.DEFAULTDB) && gd != null) {
//      final IntList pres = gd.resources.docs(qi.original);
//      return DBNodeSeq.get(pres, gd, true, qi.original.isEmpty());
//    }
//
//    // merge input with base directory
//    final String in = baseIO != null ? baseIO.merge(qi.original).path() : null;
//
//    // check currently opened collections
//    if(in != null) {
//      final String[] names = { in, qi.original };
//      final int cs = colls.size();
//      for(int c = 0; c < cs; c++) {
//        final String name = collNames.get(c);
//        if(Prop.CASE ? Strings.eq(name, names) : Strings.eqic(name, names)) return colls.get(c);
//      }
//    }
//
//    // check currently opened databases
//    Data dt = null;
//    for(final Data data : datas) {
//      // return database instance with the same name or file path
//      final String n = data.meta.name;
//      if(Prop.CASE ? n.equals(qi.db) : n.equalsIgnoreCase(qi.db) ||
//          IO.get(data.meta.original).eq(qi.input)) {
//        dt = data;
//        break;
//      }
//    }
//
//    // open new database, or create new instance
//    if(dt == null) dt = open(qi);
//    if(dt == null) dt = create(qi, false, baseIO, info);
//    return DBNodeSeq.get(dt.resources.docs(qi.path), dt, true, qi.path.isEmpty());
  }

  /**
   * Returns a reference to the updates.
   * @return updates
   */
  public Updates updates() {
    if(updates == null) updates = new Updates();
    return updates;
  }

  /**
   * Returns the module loader.
   * @return module loader
   */
  public ModuleLoader modules() {
    if(modules == null) modules = new ModuleLoader(qc.options);
    return modules;
  }

//  /**
//   * Removes and closes a database if it has not been added by the global context.
//   * @param name name of database to be removed
//   */
//  public void remove(final String name) {
//    final int ds = datas.size();
//    for(int d = globalData ? 1 : 0; d < ds; d++) {
//      final Data data = datas.get(d);
//      if(data.meta.name.equals(name)) {
//        Close.close(data, qc.context);
//        datas.remove(d);
//        break;
//      }
//    }
//  }

  /**
   * Returns the globally opened database.
   * @return database or {@code null} if no database is globally opened
   */
  Data globalData() {
    return globalData ? datas.get(0) : null;
  }

  /**
   * Returns a valid reference if a file is found in the specified path or the static base uri.
   * Otherwise, returns an error.
   * @param input query input
   * @param baseIO base IO
   * @param info input info
   * @return input source, or exception
   * @throws QueryException query exception
   */
  public static IO checkPath(final QueryInput input, final IO baseIO, final InputInfo info)
      throws QueryException {

    IO in = input.input;
    if(in.exists()) return in;
    if(baseIO != null) {
      in = baseIO.merge(input.original);
      if(!in.path().equals(input.original) && in.exists()) return in;
    }
    throw WHICHRES_X.get(info, in);
  }

  // TEST APIS ====================================================================================

  /**
   * Adds a document with the specified path. Only called from the test APIs.
   * @param name document identifier (may be {@code null})
   * @param path documents path
   * @param baseIO base URI
   * @throws QueryException query exception
   */
//  public void addDoc(final String name, final String path, final IO baseIO) throws QueryException {
//    final QueryInput qi = new QueryInput(path);
//    final Data d = create(qi, true, baseIO, null);
//    if(name != null) d.meta.original = name;
//  }

  /**
   * Adds a resource with the specified path. Only called from the test APIs.
   * @param uri resource uri
   * @param strings resource strings (path, encoding)
   */
  public void addResource(final String uri, final String... strings) {
    texts.put(uri, strings);
  }

  /**
   * Adds a collection with the specified paths. Only called from the test APIs.
   * @param name name of collection
   * @param paths documents paths
   * @param baseIO base URI
   * @throws QueryException query exception
   */
//  public void addCollection(final String name, final String[] paths, final IO baseIO)
//      throws QueryException {
//
//    final int ns = paths.length;
//    final DBNode[] nodes = new DBNode[ns];
//    for(int n = 0; n < ns; n++) {
//      final QueryInput qi = new QueryInput(paths[n]);
//      nodes[n] = new DBNode(create(qi, true, baseIO, null), 0, Data.DOC);
//    }
//    addCollection(ValueBuilder.value(nodes, ns, NodeType.DOC), name);
//  }

  // PRIVATE METHODS ==============================================================================
//
//  /**
//   * Tries to open the addressed database, or returns {@code null}.
//   * @param input query input
//   * @return data reference
//   */
//  private Data open(final QueryInput input) {
//    if(input.db != null) {
//      try {
//        // try to open database
//        final Context ctx = qc.context;
//        return addData(Open.open(input.db, ctx, ctx.options));
//      } catch(final IOException ex) { Util.debug(ex); }
//    }
//    return null;
//  }
//
//  /**
//   * Creates a new database instance.
//   * @param input query input
//   * @param single expect single document
//   * @param baseIO base URI
//   * @param info input info
//   * @return data reference
//   * @throws QueryException query exception
//   */
//  private Data create(final QueryInput input, final boolean single, final IO baseIO,
//      final InputInfo info) throws QueryException {
//
//    // check if new databases can be created
//    final Context context = qc.context;
//
//    // do not check input if no read permissions are given
//    if(!context.user().has(Perm.READ))
//      throw BXXQ_PERM_X.get(info, Util.info(Text.PERM_REQUIRED_X, Perm.READ));
//
//    // check if input is an existing file
//    final IO source = checkPath(input, baseIO, info);
//    if(single && source.isDir()) WHICHRES_X.get(info, baseIO);
//
//    // overwrite parsing options with default values
//    try {
//      final boolean mem = !context.options.get(MainOptions.FORCECREATE);
//      final MainOptions opts = new MainOptions(context.options);
//      return addData(CreateDB.create(source.dbname(),
//          new DirParser(source, opts), context, opts, mem));
//    } catch(final IOException ex) {
//      throw IOERR_X.get(info, ex);
//    } finally {
//      input.path = "";
//    }
//  }
//
//  /**
//   * Returns a single document node for the specified data reference.
//   * @param dt data reference
//   * @param qi query input
//   * @param info input info
//   * @return document node
//   * @throws QueryException query exception
//   */
//  private static DBNode doc(final Data dt, final QueryInput qi, final InputInfo info)
//      throws QueryException {
//
//    // get all document nodes of the specified database
//    final IntList docs = dt.resources.docs(qi.path);
//    // ensure that a single document was filtered
//    if(docs.size() == 1) return new DBNode(dt, docs.get(0), Data.DOC);
//    throw (docs.isEmpty() ? BXDB_NODOC_X : BXDB_SINGLE_X).get(info, qi.original);
//  }
//
//  /**
//   * Adds a data reference.
//   * @param data data reference to be added
//   * @return argument
//   */
//  private Data addData(final Data data) {
//    datas.add(data);
//    return data;
//  }
//
//  /**
//   * Adds a collection to the global collection list.
//   * @param coll documents of collection
//   * @param name collection name
//   */
//  private void addCollection(final Value coll, final String name) {
//    colls.add(coll);
//    collNames.add(name);
//  }
}
