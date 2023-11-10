/*
 * Copyright 2016-2023 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package zipkin2.dependencies.h2;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import zipkin2.dependencies.jdbc.JDBCDependenciesJob;

import javax.annotation.Nullable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import static com.google.common.base.Preconditions.checkNotNull;
import static zipkin2.internal.DateUtil.midnightUTC;

public class H2DependenciesJob extends JDBCDependenciesJob {
  public static H2DependenciesJob.Builder builder() {
    return new H2DependenciesJob.Builder();
  }

  public static final class Builder {
    Map<String, String> sparkProperties = new LinkedHashMap<>();

    String db = getEnv("H2_DB", "skywalking-oap-db");
    String host = getEnv("H2_HOST", "localhost");
    int port = Integer.parseInt(getEnv("H2_TCP_PORT", "1521"));
    String user = getEnv("H2_USER", "sa");
    int maxConnections = Integer.parseInt(getEnv("H2_MAX_CONNECTIONS", "10"));
    boolean useSsl = Boolean.parseBoolean(getEnv("H2_USE_SSL", "false"));

    // local[*] master lets us run & test the job locally without setting a Spark cluster
    String sparkMaster = getEnv("SPARK_MASTER", "local[*]");
    // needed when not in local mode
    String[] jars;
    Runnable logInitializer;

    // By default the job only works on traces whose first timestamp is today
    long day = midnightUTC(System.currentTimeMillis());

    /** When set, this indicates which jars to distribute to the cluster. */
    public H2DependenciesJob.Builder jars(String... jars) {
      this.jars = jars;
      return this;
    }

    /** The database to use. Defaults to "zipkin" */
    public H2DependenciesJob.Builder db(String db) {
      this.db = checkNotNull(db, "db");
      return this;
    }

    /** Defaults to localhost */
    public H2DependenciesJob.Builder host(String host) {
      this.host = checkNotNull(host, "host");
      return this;
    }

    /** Defaults to 3306 */
    public H2DependenciesJob.Builder port(int port) {
      this.port = port;
      return this;
    }

    /** MySQL authentication, which defaults to empty string. */
    public H2DependenciesJob.Builder user(String user) {
      this.user = checkNotNull(user, "user");
      return this;
    }

    /** Maximum concurrent connections, defaults to 10 */
    public H2DependenciesJob.Builder maxConnections(int maxConnections) {
      this.maxConnections = maxConnections;
      return this;
    }

    /**
     * Requires `javax.net.ssl.trustStore` and `javax.net.ssl.trustStorePassword`, defaults to
     * false
     */
    public H2DependenciesJob.Builder useSsl(boolean useSsl) {
      this.useSsl = useSsl;
      return this;
    }

    /** Day (in epoch milliseconds) to process dependencies for. Defaults to today. */
    public H2DependenciesJob.Builder day(long day) {
      this.day = midnightUTC(day);
      return this;
    }

    /** Extending more configuration of spark. */
    public H2DependenciesJob.Builder conf(Map<String, String> conf) {
      sparkProperties.putAll(conf);
      return this;
    }

    /** Ensures that logging is setup. Particularly important when in cluster mode. */
    public H2DependenciesJob.Builder logInitializer(Runnable logInitializer) {
      this.logInitializer = checkNotNull(logInitializer, "logInitializer");
      return this;
    }

    public H2DependenciesJob build() {
      return new H2DependenciesJob(this);
    }

    Builder() {
      sparkProperties.put("spark.ui.enabled", "false");
    }
  }

  final long day;
  final String dateStamp;
  final String url;
  final String user;
  final SparkConf conf;
  @Nullable
  final Runnable logInitializer;

  H2DependenciesJob(H2DependenciesJob.Builder builder) {
    this.day = builder.day;
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    this.dateStamp = df.format(new Date(builder.day));
    this.url = new StringBuilder("jdbc:h2:").append(builder.useSsl ? "ssl" : "tcp").append("://")
        .append(builder.host).append(":").append(builder.port)
        .append("/").append(builder.db).append(";")
        .append("DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false").toString();
    this.user = builder.user;
    this.conf = new SparkConf(true)
        .setMaster(builder.sparkMaster)
        .setAppName(getClass().getName());
    if (builder.jars != null) conf.setJars(builder.jars);
    for (Map.Entry<String, String> entry : builder.sparkProperties.entrySet()) {
      conf.set(entry.getKey(), entry.getValue());
      log.debug("Spark conf properties: {}={}", entry.getKey(), entry.getValue());
    }
    this.logInitializer = builder.logInitializer;
  }

  public void run() {
    Map<String, String> options = new LinkedHashMap<>();
    options.put("driver", org.h2.Driver.class.getName()); // prevents shade from skipping
    options.put("url", url);
    options.put("user", user);

    run(day, options, new JavaSparkContext(conf), logInitializer);
  }

  @Override
  protected String buildSaveLinkSql(String tableDate) {
    return "MERGE INTO zipkin_dependency_" + tableDate
        + " (id, table_name, analyze_day, parent, child, call_count, error_count) VALUES (?,?,?,?,?,?,?)";
  }

}