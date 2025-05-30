/*
 * Copyright 2020 Yelp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yelp.nrtsearch.server.geo;

import org.apache.lucene.util.SloppyMath;

public class GeoUtils {

  private static final double KM_TO_M = 1000.0;
  private static final double MI_TO_M = 1609.344;

  /**
   * convert raw distance like "12 km", "12 mi" to meters
   *
   * @param rawDistance
   * @return
   */
  public static double getDistance(String rawDistance) {
    String[] distanceList = rawDistance.split("\\s+");
    if (distanceList.length > 2) {
      throw new IllegalArgumentException("Invalid distance " + rawDistance);
    }

    try {
      double distanceNumber = Double.parseDouble(distanceList[0]);
      if (distanceList.length == 1) {
        return distanceNumber;
      }
      String distanceUnit = distanceList[1];
      return switch (distanceUnit) {
        case "m" -> distanceNumber;
        case "km" -> distanceNumber * KM_TO_M;
        case "mi" -> distanceNumber * MI_TO_M;
        default -> throw new IllegalArgumentException("Invalid distance " + rawDistance);
      };
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid distance " + rawDistance);
    }
  }

  public static double convertDistanceToADifferentUnit(double distanceNumber, String unit) {
    String distanceUnit = unit.strip().toLowerCase();
    if (distanceUnit.isEmpty() || distanceUnit.equals("m")) {
      return distanceNumber;
    } else if (distanceUnit.equals("km")) {
      return distanceNumber / KM_TO_M;
    } else if (distanceUnit.equals("mi")) {
      return distanceNumber / MI_TO_M;
    } else {
      throw new IllegalArgumentException("Invalid unit " + unit);
    }
  }

  /**
   * Return the distance (in meters) between 2 lat,lon geo points using the haversine method
   * implemented by lucene
   */
  public static double arcDistance(double lat1, double lon1, double lat2, double lon2) {
    return SloppyMath.haversinMeters(lat1, lon1, lat2, lon2);
  }
}
