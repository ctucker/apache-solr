/**
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

package org.apache.solr.common.params;

/**
 * @since solr 1.3
 */
public interface MultiCoreParams 
{
  /** What Core are we talking about **/
  public final static String CORE = "core";

  /** Persistent -- should it save the multicore state? **/
  public final static String PERSISTENT = "persistent";
  
  /** What action **/
  public final static String ACTION = "action";
  
  public enum MultiCoreAction {
    STATUS,  
    STOP,
    LOAD,
    RELOAD,
    SETASDEFAULT;
    
    public static MultiCoreAction get( String p )
    {
      if( p != null ) {
        try {
          return MultiCoreAction.valueOf( p.toUpperCase() );
        }
        catch( Exception ex ) {}
      }
      return null; 
    }
  }
}