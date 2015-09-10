package lmdb.basex;

import lmdb.util.Byte;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.log4j.Logger;
import org.basex.util.list.StringList;
import org.fusesource.lmdbjni.Database;
import org.fusesource.lmdbjni.Entry;
import org.fusesource.lmdbjni.EntryIterator;
import org.fusesource.lmdbjni.Env;
import org.fusesource.lmdbjni.Transaction;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;

import static org.fusesource.lmdbjni.Constants.FIXEDMAP;
import static org.fusesource.lmdbjni.Constants.bytes;
import static lmdb.Constants.string;

public class LmdbDataManager {
// TODO: basex-lmdb: add collections + documents cleaner based on removal flag
// TODO: basex-lmdb: make sure interrupted removeCollection operation is completed by
// TODO: basex-lmdb: checking the markings on all its documents (removeAllDocuments) on startup

    private static final Logger logger = Logger.getLogger(LmdbDataManager.class);

    private static Env env = null;
    private static Database coldb;
    private static Database pathsdb;
    private static Database namespacedb;
    private static Database elementdb;
    private static Database attributedb;
    private static Database tableaccessdb;
    private static Database textdatadb;
    private static Database attributevaldb;
    private static Database txtindexldb;
    private static Database txtindexrdb;
    private static Database attindexldb;
    private static Database attindexrdb;
    private static Database ftindexxdb;
    private static Database ftindexydb;
    private static Database ftindexzdb;

    private static final byte[] LAST_DOCUMENT_INDEX_KEY = new byte[]{0};
    private static final byte[] COLLECTION_LIST_KEY = new byte[]{1};

    public static void config(String home) {
        if(env != null) return;
        env = new Env();
        env.setMapSize(1024 * 1024 * 1024);
        env.setMaxDbs(15);
        env.open(home, FIXEDMAP);
    }

    public static void start() {
        coldb = env.openDatabase("collections");
        pathsdb = env.openDatabase("paths");
        namespacedb = env.openDatabase("namespaces");
        elementdb = env.openDatabase("element_names");
        attributedb = env.openDatabase("attribute_names");
        tableaccessdb = env.openDatabase("table_access");
        textdatadb = env.openDatabase("text_node_data");
        attributevaldb = env.openDatabase("attribute_values");
        txtindexldb = env.openDatabase("txtindexldb");
        txtindexrdb = env.openDatabase("txtindexrdb");
        attindexldb = env.openDatabase("attindexldb");
        attindexrdb = env.openDatabase("attindexrdb");
        ftindexxdb = env.openDatabase("ftindexxdb");
        ftindexydb = env.openDatabase("ftindexydb");
        ftindexzdb = env.openDatabase("ftindexzdb");
        logger.info("start");
    }

    public static void stop() {
        coldb.close();
        pathsdb.close();
        namespacedb.close();
        elementdb.close();
        attributedb.close();
        tableaccessdb.close();
        textdatadb.close();
        attributevaldb.close();
        txtindexldb.close();
        txtindexrdb.close();
        attindexldb.close();
        attindexrdb.close();
        ftindexxdb.close();
        ftindexydb.close();
        ftindexzdb.close();
        env.close();
        logger.info("stop");
    }

    public static synchronized void createCollection(final String name) throws IOException {
        try(Transaction tx = env.createWriteTransaction()) {
            byte[] cl = coldb.get(tx,COLLECTION_LIST_KEY);
            if (cl != null) {
                String collections = string(cl,1,cl.length-2);
                HashSet<String> collection = new HashSet<String>(Arrays.asList(collections.split(", ")));
                if(collection.contains(name)) return;
                collection.add(name);
                coldb.put(tx, COLLECTION_LIST_KEY, bytes(collection.toString()));
            } else {
                coldb.put(tx,COLLECTION_LIST_KEY, bytes("["+name+"]"));
            }
            tx.commit();
        }
    }

    public static List<String> listCollections() throws IOException {
        byte[] cl = coldb.get(COLLECTION_LIST_KEY);
        if (cl == null) return new ArrayList<String>();
        String[] clist = string(cl,1,cl.length-2).split(", ");
        Arrays.sort(clist);
        ArrayList<String> collection = new ArrayList<String>(clist.length);
        for(String c: clist) if(!c.endsWith("/r")) collection.add(c);
        return collection;
    }

    public static synchronized void removeCollection(final String name) throws IOException {
        byte[] cl = coldb.get(COLLECTION_LIST_KEY);
        if (cl == null) return;
        String collections = string(cl, 1, cl.length - 2);
        HashSet<String> collection = new HashSet<String>(Arrays.asList(collections.split(", ")));
        if(collection.remove(name)) {
            collection.add(name+"/r");
            coldb.put(COLLECTION_LIST_KEY, bytes(collection.toString()));
            removeAllDocuments(name);
        }
    }

    public static void createDocument(final String name, InputStream content) throws IOException {
        byte[] docid = getNextDocumentId(name);
        System.out.println("docid=" + Byte.getInt(docid));
    }

    public static List<String> listDocuments(String collection) throws IOException {
        ArrayList<String> docs = new ArrayList<String>();
        try(Transaction tx = env.createWriteTransaction()) {
            EntryIterator ei = coldb.seek(tx, bytes(collection));
            while (ei.hasNext()) {
                Entry e = ei.next();
                String key = string(e.getKey());
                if(key.endsWith("/r")) continue;
                if(!key.startsWith(collection)) break;
                docs.add(key.substring(key.indexOf('/')+1));
            }
            tx.commit();
        }
        return docs;
    }

    public static void removeDocument(final String name) throws IOException {
        try(Transaction tx = env.createWriteTransaction()) {
            byte[] docid = coldb.get(tx, bytes(name));
            if(docid == null) return;
            coldb.delete(tx, bytes(name));
            coldb.put(tx, bytes(name + "/r"), docid);
            tx.commit();
        }
    }

    private static synchronized byte[] getNextDocumentId(final String name) throws IOException {
        if(coldb.get(bytes(name)) != null) throw new IOException("document " + name + " exists");
        int i = name.indexOf('/');
        if(i > 0 && name.length() > 2) {
            String docname = name.substring(i+1);
            if(docname.indexOf('/') != -1) throw new IOException("document " + docname + " name is malformed");
            String collection = name.substring(0,i);
            if(!listCollections().contains(collection)) throw new IOException("unknown collection " + collection);
        } else {
            throw new IOException("malformed document name " + name +  " or unknown collection. 'collection_name/document_name' needed");
        }
        try(Transaction tx = env.createWriteTransaction()) {
            byte[] docid = coldb.get(tx,LAST_DOCUMENT_INDEX_KEY);
            if(docid == null) {
                docid = new byte[]{0,0,0,0};
            } else {
                Byte.setInt(Byte.getInt(docid)+1,docid);
            }
            coldb.put(tx, LAST_DOCUMENT_INDEX_KEY, docid);
            coldb.put(tx,bytes(name),docid);
            tx.commit();
            return docid;
        }
    }

    private static synchronized void removeAllDocuments(String collection) {
        try(Transaction tx = env.createWriteTransaction()) {
            EntryIterator ei = coldb.seek(tx, bytes(collection));
            while (ei.hasNext()) {
                Entry e = ei.next();
                if (!string(e.getKey()).startsWith(collection)) break;
                if(coldb.delete(tx, e.getKey())) coldb.put(tx, bytes(string(e.getKey())+"/r"), e.getValue());
            }
            tx.commit();
        }
    }

    public static void t() {
        coldb.put(key(10,0),bytes("a"));
        coldb.put(key(10,10),bytes("a"));
        coldb.put(key(20,100),bytes("a"));
        coldb.put(key(10,11),bytes("b"));
        coldb.put(key(20,101),bytes("b"));
    }

    public static void main(String[] arg) throws Exception {
        LmdbDataManager.config("/home/mscastro/dev/basex-lmdb/db");
        LmdbDataManager.start();
        LmdbDataManager.createCollection("c1");
        LmdbDataManager.createCollection("c2");
        LmdbDataManager.createCollection("c3");
        LmdbDataManager.removeCollection("c1");
        LmdbDataManager.createCollection("c1");
        LmdbDataManager.removeCollection("c1");
        LmdbDataManager.createCollection("c4");
        LmdbDataManager.createDocument("c4/d0", new ByteArrayInputStream(new byte[]{}));
        LmdbDataManager.createDocument("c2/d0", new ByteArrayInputStream(new byte[]{}));
        LmdbDataManager.createDocument("c4/d1", new ByteArrayInputStream(new byte[]{}));
        LmdbDataManager.createDocument("c4/d2", new ByteArrayInputStream(new byte[]{}));

//        System.out.println(LmdbDataManager.listDocuments("c4"));

        //LmdbDataManager.removeCollection("c4");

        LmdbDataManager.removeDocument("c4/d1");

//        System.out.println(LmdbDataManager.listDocuments("c4"));

//        System.out.println(LmdbDataManager.listCollections());

        LmdbDataManager.t();

        //System.err.println(Hex.encodeHexString(key(10, 11)));

        try(Transaction tx = env.createWriteTransaction()) {

            EntryIterator ei = coldb.seek(tx, key(10,0));
            while (ei.hasNext()) {
                Entry e = ei.next();
                if(Byte.getInt(e.getKey()) != 10) break;
                System.err.println(Hex.encodeHexString(e.getKey()) + ":" + string(e.getValue()));
            }
            tx.commit();
        }

//        System.out.println("-----------------------------------------------------------------------");

//        try(Transaction tx = env.createReadTransaction()) {
//            EntryIterator ei = coldb.iterate(tx);
//            while (ei.hasNext()) {
//                Entry e = ei.next();
//                System.err.println(Hex.encodeHexString(e.getKey()) + ":" + string(e.getValue()));
//            }
//        }

        LmdbDataManager.stop();
    }

    private static byte[] key(int did, int pre) {
        byte[] docid = lmdb.util.Byte.getBytes(did);
        return new byte[] {
                docid[0],
                docid[1],
                docid[2],
                docid[3],
                (byte)(pre >> 24),
                (byte)(pre >> 16),
                (byte)(pre >> 8),
                (byte)pre
        };
    }
}
