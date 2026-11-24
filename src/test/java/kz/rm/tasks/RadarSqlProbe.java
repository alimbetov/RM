package kz.rm.tasks;

import org.springframework.jdbc.datasource.DelegatingDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Test-only JDBC boundary. Capture executions (not prepare calls), never bind values. */
final class RadarSqlProbe {
    private static final ThreadLocal<Trace> ACTIVE=new ThreadLocal<>();
    private static final Pattern BUSINESS=Pattern.compile("\\bcrm\\s*\\.");
    private static final Pattern TASK=Pattern.compile("\\bcrm\\s*\\.\\s*task_item\\b");
    record Entry(boolean select,boolean business,boolean task,boolean batch,boolean injected) {}
    static final class Trace implements AutoCloseable {
        final List<Entry> entries=new ArrayList<>();
        int failuresRemaining;
        Trace(boolean fail){failuresRemaining=fail?1:0;}
        List<Entry> entries(){return List.copyOf(entries);}
        long taskSelects(){return entries.stream().filter(e->e.select()&&e.task()).count();}
        long businessStatements(){return entries.stream().filter(Entry::business).count();}
        @Override public void close(){ACTIVE.remove();}
    }
    static Trace capture(boolean fail){
        if(ACTIVE.get()!=null)throw new IllegalStateException("nested capture");
        Trace t=new Trace(fail);ACTIVE.set(t);return t;
    }
    private static String normalize(String sql){
        // Remove leading/block SQL comments and normalize identifiers for the
        // bounded known JDBC/Hibernate query vocabulary. Not a general parser.
        return sql.replaceAll("(?s)/\\*.*?\\*/"," ").replaceAll("(?m)^\\s*--[^\\n]*"," ")
            .replace("\"","").strip().toLowerCase(Locale.ROOT);
    }
    private static void executed(String sql,boolean batch)throws SQLException{
        Trace t=ACTIVE.get();if(t==null)return;
        String n=normalize(sql==null?"":sql);
        boolean select=n.startsWith("select ")||n.startsWith("select\n")||n.startsWith("select\t");
        boolean business=BUSINESS.matcher(n).find(),task=TASK.matcher(n).find();
        boolean inject=select&&task&&t.failuresRemaining>0;
        t.entries.add(new Entry(select,business,task,batch,inject));
        if(inject){t.failuresRemaining--;throw new SQLException("RADAR_TEST_SQL_FAILURE","08006");}
    }
    static final class Wrapped extends DelegatingDataSource implements AutoCloseable {
        Wrapped(DataSource delegate){super(delegate);}
        @Override public Connection getConnection()throws SQLException{return connection(super.getConnection());}
        @Override public Connection getConnection(String u,String p)throws SQLException{return connection(super.getConnection(u,p));}
        @Override public void close()throws Exception{
            if(getTargetDataSource() instanceof AutoCloseable c)c.close();
        }
    }
    private static Object call(Object target,Method m,Object[] args)throws Throwable{
        try{return m.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
    private static Connection connection(Connection real){
        return (Connection)Proxy.newProxyInstance(RadarSqlProbe.class.getClassLoader(),new Class<?>[]{Connection.class},
            (self,m,args)->{
                String name=m.getName();
                if(name.equals("unwrap")&&((Class<?>)args[0]).isInstance(self))return self;
                if(name.equals("isWrapperFor")&&((Class<?>)args[0]).isInstance(self))return true;
                if(name.equals("unwrap")&&ACTIVE.get()!=null)throw new SQLException("RADAR_PROBE_UNWRAP_UNSUPPORTED");
                Object result=call(real,m,args);
                if(result instanceof Statement st&&(name.equals("prepareStatement")||name.equals("prepareCall")||name.equals("createStatement")))
                    return statement((Connection)self,st,args!=null&&args.length>0&&args[0] instanceof String sql?sql:null);
                return result;
            });
    }
    private static Statement statement(Connection owner,Statement real,String preparedSql){
        Class<?> api=real instanceof CallableStatement?CallableStatement.class:
            real instanceof PreparedStatement?PreparedStatement.class:Statement.class;
        return (Statement)Proxy.newProxyInstance(RadarSqlProbe.class.getClassLoader(),new Class<?>[]{api},
            (self,m,args)->{
                String name=m.getName();
                if(name.equals("getConnection"))return owner;
                if(name.equals("unwrap")&&((Class<?>)args[0]).isInstance(self))return self;
                if(name.equals("isWrapperFor")&&((Class<?>)args[0]).isInstance(self))return true;
                if(name.equals("unwrap")&&ACTIVE.get()!=null)throw new SQLException("RADAR_PROBE_UNWRAP_UNSUPPORTED");
                if(name.startsWith("execute")){
                    boolean batch=name.equals("executeBatch")||name.equals("executeLargeBatch");
                    String sql=args!=null&&args.length>0&&args[0] instanceof String x?x:preparedSql;
                    executed(sql,batch);
                }
                // Record batch enqueue as a non-read operation in a read scope,
                // even before the eventual execution. Unknown SQL fails closed.
                if(name.equals("addBatch")){
                    String sql=args!=null&&args.length>0&&args[0] instanceof String x?x:preparedSql;
                    executed(sql,true);
                }
                return call(real,m,args);
            });
    }
}
