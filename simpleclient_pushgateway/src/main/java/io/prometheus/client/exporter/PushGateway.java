package io.prometheus.client.exporter;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;

/**
 * Export metrics via the Prometheus Pushgateway.
 * <p>
 * The Prometheus Pushgateway exists to allow ephemeral and batch jobs to expose their metrics to Prometheus.
 * Since these kinds of jobs may not exist long enough to be scraped, they can instead push their metrics
 * to a Pushgateway. This class allows pushing the contents of a {@link CollectorRegistry} to
 * a Pushgateway.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 *   void executeBatchJob() throws Exception {
 *     CollectorRegistry registry = new CollectorRegistry();
 *     Gauge duration = Gauge.build()
 *         .name("my_batch_job_duration_seconds").help("Duration of my batch job in seconds.").register(registry);
 *     Gauge.Timer durationTimer = duration.startTimer();
 *     try {
 *       // Your code here.
 *
 *       // This is only added to the registry after success,
 *       // so that a previous success in the Pushgateway isn't overwritten on failure.
 *       Gauge lastSuccess = Gauge.build()
 *           .name("my_batch_job_last_success").help("Last time my batch job succeeded, in unixtime.").register(registry);
 *       lastSuccess.setToCurrentTime();
 *     } finally {
 *       durationTimer.setDuration();
 *       PushGateway pg = new PushGateway("127.0.0.1:9091");
 *       pg.pushAdd(registry, "my_batch_job");
 *     }
 *   }
 * }
 * </pre>
 * <p>
 * See <a href="https://github.com/prometheus/pushgateway">https://github.com/prometheus/pushgateway</a>
 */
public class PushGateway {

  private static final int MILLISECONDS_PER_SECOND = 1000;

  // Visible for testing.
  protected final String gatewayBaseURL;

  private HttpConnectionFactory connectionFactory = new DefaultHttpConnectionFactory();

  /**
   * Construct a Pushgateway, with the given address.
   * <p>
   * @param address  host:port or ip:port of the Pushgateway.
   */
  public PushGateway(String address) {
    this(createURLSneakily("http://" + address));
  }

  /**
   * Construct a Pushgateway, with the given URL.
   * <p>
   * @param serverBaseURL the base URL and optional context path of the Pushgateway server.
   */
  public PushGateway(URL serverBaseURL) {
    this.gatewayBaseURL = URI.create(serverBaseURL.toString() + "/metrics/")
      .normalize()
      .toString();
  }

  public void setConnectionFactory(HttpConnectionFactory connectionFactory) {
    this.connectionFactory = connectionFactory;
  }

  /**
   * Creates a URL instance from a String representation of a URL without throwing a checked exception.
   * Required because you can't wrap a call to another constructor in a try statement.
   *
   * @param urlString the String representation of the URL.
   * @return The URL instance.
   */
  private static URL createURLSneakily(final String urlString) {
    try {
      return new URL(urlString);
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Pushes all metrics in a registry, replacing all those with the same job and no grouping key.
   * <p>
   * This uses the PUT HTTP method.
  */
  public void push(CollectorRegistry registry, String job) throws IOException {
    doRequest(registry, job, null, "PUT");
  }

  /**
   * Pushes all metrics in a Collector, replacing all those with the same job and no grouping key.
   * <p>
   * This is useful for pushing a single Gauge.
   * <p>
   * This uses the PUT HTTP method.
  */
  public void push(Collector collector, String job) throws IOException {
    CollectorRegistry registry = new CollectorRegistry();
    collector.register(registry);
    push(registry, job);
  }

  /**
   * Pushes all metrics in a registry, replacing all those with the same job and grouping key.
   * <p>
   * This uses the PUT HTTP method.
  */
  public void push(CollectorRegistry registry, String job, Map<String, String> groupingKey) throws IOException {
    doRequest(registry, job, groupingKey, "PUT");
  }

  /**
   * Pushes all metrics in a Collector, replacing all those with the same job and grouping key.
   * <p>
   * This is useful for pushing a single Gauge.
   * <p>
   * This uses the PUT HTTP method.
  */
  public void push(Collector collector, String job, Map<String, String> groupingKey) throws IOException {
    CollectorRegistry registry = new CollectorRegistry();
    collector.register(registry);
    push(registry, job, groupingKey);
  }

  /**
   * Pushes all metrics in a registry, replacing only previously pushed metrics of the same name and job and no grouping key.
   * <p>
   * This uses the POST HTTP method.
  */
  public void pushAdd(CollectorRegistry registry, String job) throws IOException {
    doRequest(registry, job, null, "POST");
  }

  /**
   * Pushes all metrics in a Collector, replacing only previously pushed metrics of the same name and job and no grouping key.
   * <p>
   * This is useful for pushing a single Gauge.
   * <p>
   * This uses the POST HTTP method.
  */
  public void pushAdd(Collector collector, String job) throws IOException {
    CollectorRegistry registry = new CollectorRegistry();
    collector.register(registry);
    pushAdd(registry, job);
  }

  /**
   * Pushes all metrics in a registry, replacing only previously pushed metrics of the same name, job and grouping key.
   * <p>
   * This uses the POST HTTP method.
  */
  public void pushAdd(CollectorRegistry registry, String job, Map<String, String> groupingKey) throws IOException {
    doRequest(registry, job, groupingKey, "POST");
  }

  /**
   * Pushes all metrics in a Collector, replacing only previously pushed metrics of the same name, job and grouping key.
   * <p>
   * This is useful for pushing a single Gauge.
   * <p>
   * This uses the POST HTTP method.
  */
  public void pushAdd(Collector collector, String job, Map<String, String> groupingKey) throws IOException {
    CollectorRegistry registry = new CollectorRegistry();
    collector.register(registry);
    pushAdd(registry, job, groupingKey);
  }


  /**
   * Deletes metrics from the Pushgateway.
   * <p>
   * Deletes metrics with no grouping key and the provided job.
   * This uses the DELETE HTTP method.
  */
  public void delete(String job) throws IOException {
    doRequest(null, job, null, "DELETE");
  }

  /**
   * Deletes metrics from the Pushgateway.
   * <p>
   * Deletes metrics with the provided job and grouping key.
   * This uses the DELETE HTTP method.
  */
  public void delete(String job, Map<String, String> groupingKey) throws IOException {
    doRequest(null, job, groupingKey, "DELETE");
  }

  void doRequest(CollectorRegistry registry, String job, Map<String, String> groupingKey, String method) throws IOException {
    String url = gatewayBaseURL;
    if (job.contains("/")) {
      url += "job@base64/" + base64url(job);
    } else {
      url += "job/" + URLEncoder.encode(job, "UTF-8");
    }

    if (groupingKey != null) {
      for (Map.Entry<String, String> entry: groupingKey.entrySet()) {
        if (entry.getValue().isEmpty()) {
          url += "/" + entry.getKey() + "@base64/=";
        } else if (entry.getValue().contains("/")) {
          url += "/" + entry.getKey() + "@base64/" + base64url(entry.getValue());
        } else {
          url += "/" + entry.getKey() + "/" + URLEncoder.encode(entry.getValue(), "UTF-8");
        }
      }
    }
    HttpURLConnection connection = connectionFactory.create(url);
    connection.setRequestProperty("Content-Type", TextFormat.CONTENT_TYPE_004);
    if (!method.equals("DELETE")) {
      connection.setDoOutput(true);
    }
    connection.setRequestMethod(method);

    connection.setConnectTimeout(10 * MILLISECONDS_PER_SECOND);
    connection.setReadTimeout(10 * MILLISECONDS_PER_SECOND);
    connection.connect();

    try {
      if (!method.equals("DELETE")) {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(), "UTF-8"));
        TextFormat.write004(writer, registry.metricFamilySamples());
        writer.flush();
        writer.close();
      }

      int response = connection.getResponseCode();
      if (response/100 != 2) {
        String errorMessage;
        InputStream errorStream = connection.getErrorStream();
        if(errorStream != null) {
          String errBody = readFromStream(errorStream);
          errorMessage = "Response code from " + url + " was " + response + ", response body: " + errBody;
        } else {
          errorMessage = "Response code from " + url + " was " + response;
        }
        throw new IOException(errorMessage);
      }
    } finally {
      connection.disconnect();
    }
  }

  private static String base64url(String v) {
    // Per RFC4648 table 2. We support Java 6, and java.util.Base64 was only added in Java 8,
    try {
      return Base64.encodeToString(v.getBytes("UTF-8")).replace("+", "-").replace("/", "_");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);  // Unreachable.
    }
  }

  /**
   * Returns a grouping key with the instance label set to the machine's IP address.
   * <p>
   * This is a convenience function, and should only be used where you want to
   * push per-instance metrics rather than cluster/job level metrics.
  */
  public static Map<String, String> instanceIPGroupingKey() throws UnknownHostException {
    Map<String, String> groupingKey = new HashMap<String, String>();
    groupingKey.put("instance", InetAddress.getLocalHost().getHostAddress());
    return groupingKey;
  }

  private static String readFromStream(InputStream is) throws IOException {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int length;
    while ((length = is.read(buffer)) != -1) {
      result.write(buffer, 0, length);
    }
    return result.toString("UTF-8");
  }
}
