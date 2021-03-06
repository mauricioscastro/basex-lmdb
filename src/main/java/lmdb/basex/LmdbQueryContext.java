package lmdb.basex;

import org.basex.build.json.JsonOptions;
import org.basex.build.json.JsonSerialOptions;
import org.basex.core.MainOptions;
import org.basex.io.IOContent;
import org.basex.io.serial.SerialMethod;
import org.basex.io.serial.Serializer;
import org.basex.io.serial.SerializerOptions;
import org.basex.query.QueryContext;
import org.basex.query.QueryException;
import org.basex.query.iter.Iter;
import org.basex.query.value.item.Item;
import org.basex.query.value.node.DBNode;
import org.basex.query.value.type.NodeType;
import org.basex.query.value.type.SeqType;
import org.basex.util.options.Options;
import org.fusesource.lmdbjni.Transaction;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

public class LmdbQueryContext extends QueryContext implements Closeable {

    private Transaction tx = null;

    public LmdbQueryContext(final String query) throws QueryException {
        this(query, null, null, new MainOptions(), null);
    }

    public LmdbQueryContext(final String query, final String context) throws QueryException {
        this(query, context, null, new MainOptions(), null);
    }

    public LmdbQueryContext(final String query, final MainOptions opt) throws QueryException {
        this(query, null, null, opt, null);
    }

    public LmdbQueryContext(final String query, Transaction tx) throws QueryException {
        this(query, null, null, new MainOptions(), tx);
    }

    public LmdbQueryContext(final String query, final Map<String,Object> var, final MainOptions opt) throws QueryException {
        this(query, null, var, opt, null);
    }


    public LmdbQueryContext(final String query, final String context, final Map<String,Object> var) throws QueryException {
        this(query, context, var, new MainOptions(), null);
    }

    public LmdbQueryContext(final String query, final String context, final Map<String,Object> var, final MainOptions opt, Transaction tx) throws QueryException {
        super(opt, null);
        resources = new LmdbQueryResources(this);
        this.tx = tx;
        try {
            parse(query);
            if (context != null) context(new DBNode(new IOContent(context)));
            if (var != null && var.size() > 0) for (String k : var.keySet()) bind(k, var.get(k));
            compile();
        } catch (QueryException qe) {
            try {
                close();
            } catch (Exception i) {
            }
            throw qe;
        } catch (IOException ioe) {
            try {
                close();
            } catch (Exception i) {
            }
            throw new QueryException(ioe);
        }
    }

    public Transaction tx() {
        if(tx != null) return tx;
        if(LmdbDataManager.env == null) return null;
        tx = updating ? LmdbDataManager.env.createWriteTransaction() : LmdbDataManager.env.createReadTransaction();
        return tx;
    }

    @Override
    public void close() throws IOException {
        super.close();
        if(tx == null) return;
        if(!tx.isReadOnly()) tx.commit();
        else tx.close();
        tx = null;
    }


    public void run(OutputStream result) throws QueryException {
        run(result, null, true);
    }

    public void run(OutputStream result, boolean indent) throws QueryException {
        run(result, null, indent);
    }

    public void run(OutputStream result, String method, String indent, String jsonFormat) throws QueryException {
        run(result, method, Boolean.parseBoolean(indent), jsonFormat);
    }

    public void run(OutputStream result, String method, String indent) throws QueryException {
        run(result, method, Boolean.parseBoolean(indent));
    }

    public void run(OutputStream result, String method, boolean indent) throws QueryException {
        run(result, method, indent, "basic");
    }

    public void run(OutputStream result, String method, boolean indent, String jsonFormat) throws QueryException {
        try(Serializer s = Serializer.get(result, getSerializerOptions(method, indent, jsonFormat))) {
            Iter iter = iter();
            Item i = null;
            while ((i = iter.next()) != null) {
                if (i.type == NodeType.ATT || i.type == NodeType.NSP || i.type.instanceOf(SeqType.ANY_ARRAY)) {
                    result.flush();
                    result.write(i.toString().getBytes());
                } else {
                    s.serialize(i);
                }
            }
        } catch(IOException ioe) {
            throw new QueryException(ioe);
        }
    }

    public static String queryString(final String query) throws QueryException {
        return queryString(query,null);
    }

    public static String queryString(final String query, final String context) throws QueryException {
        try(LmdbQueryContext ctx = new LmdbQueryContext(query, context)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
            ctx.run(bos,false);
            return bos.toString();
        } catch(IOException ioe) {
            throw new QueryException(ioe);
        }
    }

    public InputStream queryStream(final String query, final String context, final Map<String,Object> var, final String method) throws QueryException {
        final ByteArrayOutputStream result = new ByteArrayOutputStream();
        final LmdbQueryContext ctx = new LmdbQueryContext(query, context, var);
        try {
            return new InputStream() {
                private Serializer s = Serializer.get(result, getSerializerOptions(method));
                private Iter iter = ctx.iter();
                private Item i = null;
                private byte[] b = null;
                private int off = -1;
                public void close() throws IOException {
                    s.close();
                    ctx.close();
                }
                public int read() throws IOException {
                    if((b == null || off >= b.length) && !next()) return -1;
                    return (int)b[off++];
                }
                private boolean next() throws IOException {
                    try {
                        if((i = iter.next()) == null) return false;
                        result.reset();
                        if(i.type == NodeType.ATT || i.type == NodeType.NSP || i.type.instanceOf(SeqType.ANY_ARRAY)) {
                            result.flush();
                            result.write(i.toString().getBytes());
                        } else {
                            s.serialize(i);
                        }
                        b = result.toByteArray();
                        off = 0;
                        return true;
                    } catch(QueryException qe) {
                        throw new IOException(qe);
                    }
                }
            };
        } catch(IOException ioe) {
            try {
                ctx.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            throw new QueryException(ioe);
        }
    }

    private static SerializerOptions getSerializerOptions(String method, boolean indent, String jsonFormat) {
        method = method == null ? "text/xml" : method.toLowerCase();
        SerializerOptions opt = new SerializerOptions();
        opt.set(SerializerOptions.METHOD, SerialMethod.XML);
        if(method != null && !method.contains("/xml")) {
            if (method.contains("plain")) opt.set(SerializerOptions.METHOD, SerialMethod.TEXT);
            else if (method.contains("xhtml")) opt.set(SerializerOptions.METHOD, SerialMethod.XHTML);
            else if (method.contains("html")) opt.set(SerializerOptions.METHOD, SerialMethod.HTML);
            else if (method.contains("json") || method.contains("javascript")) {
                JsonSerialOptions jsopt = new JsonSerialOptions();
                jsopt.set(JsonOptions.FORMAT, jsonFormat);
                opt.set(SerializerOptions.METHOD, SerialMethod.JSON);
                opt.set(SerializerOptions.JSON, jsopt);
            }
            else if (method.contains("raw")) opt.set(SerializerOptions.METHOD, SerialMethod.RAW);
        }
        opt.set(SerializerOptions.INDENT, indent ? Options.YesNo.YES : Options.YesNo.NO);
        return opt;
    }

    private static SerializerOptions getSerializerOptions(String method, boolean indent) {
        return getSerializerOptions(method, indent, "basic");
    }

    private static SerializerOptions getSerializerOptions(String method) {
        return getSerializerOptions(method, false);
    }
}
