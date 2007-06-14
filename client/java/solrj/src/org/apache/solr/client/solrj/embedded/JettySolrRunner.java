/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.client.solrj.embedded;

import java.io.IOException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.solr.core.Config;
import org.apache.solr.core.SolrCore;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.servlet.SolrDispatchFilter;
import org.apache.solr.servlet.SolrServlet;
import org.apache.solr.servlet.SolrUpdateServlet;
import org.mortbay.jetty.Handler;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.FilterHolder;
import org.mortbay.log.Logger;

/**
 * Run solr using jetty
 * 
 * @author ryan
 * @version $Id$
 * @since solr 1.3
 */
public class JettySolrRunner 
{
  Server server;
  FilterHolder dispatchFilter;
  
  public JettySolrRunner( String context, int port )
  {
    this.init( context, port );
  }
  
//  public JettySolrRunner( String context, String home, String dataDir, int port, boolean log )
//  {
//    if(!log) {
//      System.setProperty("org.mortbay.log.class", NoLog.class.getName() );
//      System.setProperty("java.util.logging.config.file", home+"/conf/logging.properties");
//      NoLog noLogger = new NoLog();
//      org.mortbay.log.Log.setLog(noLogger);
//    }
//
//    // Initalize JNDI
//    Config.setInstanceDir(home);
//    new SolrCore(dataDir, new IndexSchema(home+"/conf/schema.xml"));
//    this.init( context, port );
//  }
  
  private void init( String context, int port )
  {
    server = new Server( port );    
    
    // Initialize the servlets
    Context root = new Context( server, context, Context.SESSIONS );
    root.addServlet( SolrServlet.class, "/select" );
    root.addServlet( SolrUpdateServlet.class, "/update" );

    // for some reason, there must be a servlet for this to get applied
    root.addServlet( Servlet404.class, "/*" );
    dispatchFilter = root.addFilter( SolrDispatchFilter.class, "*", Handler.REQUEST );
  }

  //------------------------------------------------------------------------------------------------
  //------------------------------------------------------------------------------------------------
  
  public void start() throws Exception
  {
    if(!server.isRunning() ) {
      server.start();
    }
  }
  
  public void stop() throws Exception
  {
    if( server.isRunning() ) {
      server.stop();
    }
  }
  
  //--------------------------------------------------------------
  //--------------------------------------------------------------
    
  /** 
   * This is a stupid hack to give jetty something to attach to
   */
  public static class Servlet404 extends HttpServlet
  {
    @Override
    public void service(HttpServletRequest req, HttpServletResponse res ) throws IOException
    {
      res.sendError( 404, "Can not find: "+req.getRequestURI() );
    }
  }
}


class NoLog implements Logger
{    
  private static boolean debug = System.getProperty("DEBUG",null)!=null;
  private final String name;
      
  public NoLog()
  {
    this(null);
  }
  
  public NoLog(String name)
  {    
    this.name=name==null?"":name;
  }
  
  public boolean isDebugEnabled()
  {
    return debug;
  }
  
  public void setDebugEnabled(boolean enabled)
  {
    debug=enabled;
  }
  
  public void info(String msg,Object arg0, Object arg1) {}
  public void debug(String msg,Throwable th){}
  public void debug(String msg,Object arg0, Object arg1){}
  public void warn(String msg,Object arg0, Object arg1){}
  public void warn(String msg, Throwable th){}

  public Logger getLogger(String name)
  {
    if ((name==null && this.name==null) ||
      (name!=null && name.equals(this.name)))
      return this;
    return new NoLog(name);
  }
  
  @Override
  public String toString()
  {
    return "NOLOG["+name+"]";
  }
}
