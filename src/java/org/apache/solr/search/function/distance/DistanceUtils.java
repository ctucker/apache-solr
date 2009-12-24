package org.apache.solr.search.function.distance;

import org.apache.solr.common.SolrException;
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


/**
 * Useful distance utiltities
 *
 **/
public class DistanceUtils {
  public static final double DEGREES_TO_RADIANS = Math.PI / 180.0;
  public static final double RADIANS_TO_DEGREES = 180.0 / Math.PI;

  /**
   * @see org.apache.solr.search.function.distance.HaversineFunction
   * 
   * @param x1 The x coordinate of the first point
   * @param y1 The y coordinate of the first point
   * @param x2 The x coordinate of the second point
   * @param y2 The y coordinate of the second point
   * @param radius The radius of the sphere
   * @return The distance between the two points, as determined by the Haversine formula.
   */
  public static double haversine(double x1, double y1, double x2, double y2, double radius){
    double result = 0;
    //make sure they aren't all the same, as then we can just return 0
    if ((x1 != x2) || (y1 != y2)) {
      double diffX = x1 - x2;
      double diffY = y1 - y2;
      double hsinX = Math.sin(diffX * 0.5);
      double hsinY = Math.sin(diffY * 0.5);
      double h = hsinX * hsinX +
              (Math.cos(x1) * Math.cos(x2) * hsinY * hsinY);
      result = (radius * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h)));
    }
    return result;
  }

  /**
   * Given a string containing <i>dimension</i> values encoded in it, separated by commas, return a String array of length <i>dimension</i>
   * containing the values.
   * @param out A preallocated array.  Must be size dimension.  If it is not it will be resized.
   * @param externalVal The value to parse
   * @param dimension The expected number of values for the point
   * @return An array of the values that make up the point (aka vector)
   *
   * @throws {@link SolrException} if the dimension specified does not match the number of values in the externalValue.
   */
  public static String[] parsePoint(String[] out, String externalVal, int dimension) {
    //TODO: Should we support sparse vectors?
    if (out==null || out.length != dimension) out=new String[dimension];
    int idx = externalVal.indexOf(',');
    int end = idx;
    int start = 0;
    int i = 0;
    if (idx == -1 && dimension == 1 && externalVal.length() > 0){//we have a single point, dimension better be 1
      out[0] = externalVal.trim();
      i = 1;
    }
    else if (idx > 0) {//if it is zero, that is an error
      //Parse out a comma separated list of point values, as in: 73.5,89.2,7773.4
      for (; i < dimension; i++){
        while (start<end && externalVal.charAt(start)==' ') start++;
        while (end>start && externalVal.charAt(end-1)==' ') end--;
        out[i] = externalVal.substring(start, end);
        start = idx+1;
        end = externalVal.indexOf(',', start);
        if (end == -1){
          end = externalVal.length();
        }
      }
    } 
    if (i != dimension){
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "incompatible dimension (" + dimension +
              ") and values (" + externalVal + ").  Only " + i + " values specified");
    }
    return out;
  }
}
