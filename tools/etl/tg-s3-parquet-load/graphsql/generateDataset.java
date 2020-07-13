package graphsql;

import java.io.*;
import java.util.*;
import java.lang.StringBuffer;
import java.lang.StringBuilder;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.SimpleFormatter;
import java.util.logging.FileHandler;

import scala.collection.JavaConversions;
import scala.collection.Seq;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.*;
import org.apache.spark.sql.types.*;
import org.json.*;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.services.s3.*;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.s3.iterable.*;
import com.amazonaws.auth.BasicAWSCredentials;

public class generateDataset implements Serializable {

  static String S3Prefix = "s3a://";
  static String S3FileSeparator = "/";
  static String localPrefix = "file://";

  static String awsAccessKey;
  static String awsSecretKey;
  static String bucket_name;
  static String tg_token;
  static Boolean readFileOnly = false;

  static String[] schema_folders;

  private static Logger logger;

  public static void main(String[] args) {
    String properties_file = "";
    String target_host = "";
    String target_path = "";

    int times = 1;

    if (args.length < 3) {
      System.out.println("Arguments wrongs. augument: properties_file target_host target_path");
      System.exit(1);
    } else {
      properties_file = args[0];
      target_host = args[1];
      target_path = args[2];
    }

    if (args.length > 3) {
      times = Integer.parseInt(args[3]);
    }

    String loggerFolder = "/tmp/sparkLoader";
    if (args.length > 4) {
      loggerFolder = args[4];
    }

    logger = Logger.getLogger("SparkLoader");
    try {
      File directory = new File(loggerFolder);
      if (!directory.exists()) {
        directory.mkdirs();
      }
      FileHandler handler = new FileHandler(loggerFolder + "/sparkLoading.%g.log", 10 * 1024 * 1024, 30, false);
      handler.setFormatter(new SimpleFormatter());
      logger.addHandler(handler);
    } catch (Exception e) {
      logger = null;
      System.out.println("Logger init failed");
      System.exit(1);
    }

    try {
      // read from properties file
      Properties prop = new Properties();
      prop.load(new FileInputStream(properties_file));

      awsAccessKey = prop.getProperty("awsAccessKey");
      awsSecretKey = prop.getProperty("awsSecretKey");
      bucket_name = prop.getProperty("bucket_name");
      schema_folders = prop.getProperty("schema_folders").split(",");
      tg_token = prop.getProperty("tg_token");

      String rest_endpoint = prop.getProperty("rest_endpoint");

      //create the token
      String bearer = "Bearer ";
      String final_token = bearer + tg_token;

      new generateDataset().run(target_host, target_path, rest_endpoint, times, final_token);
    } catch (IOException ex) {
      ex.printStackTrace();
    }
  }

  private static void logMsg(String msg, Level level, Boolean tee2Std) {
    if (logger == null) return;
    logger.log(level, msg);
    if (tee2Std) System.out.println(msg);
  }

  private static void logInfo(String msg, Boolean tee2Std) {
    logMsg(msg, Level.INFO, tee2Std);
  }

  private static void logInfo(String msg) {
    logInfo(msg, false);
  }

  private static void logWarning(String msg) {
    logMsg(msg, Level.WARNING, false);
  }


  public void run(String target_host, String target_path, String rest_endpoint, int times, String tg_token) {
    // create spark context and spark sql instance
    SparkSession sc = SparkSession.builder().appName("Uber").getOrCreate();
    sc.conf().set("fs.s3a.attempts.maximum", "30");
    sc.conf().set("fs.s3.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem");
    // conf.set("mapreduce.fileoutputcommitter.algorithm.version", "2")

    // set the S3 client configuration and create a client connected to S3.
    ClientConfiguration configuration = new ClientConfiguration();
    configuration.setMaxErrorRetry(10);
    configuration.setConnectionTimeout(501000);
    configuration.setSocketTimeout(501000);
    //configuration.setUseTcpKeepAlive(true);
    AmazonS3Client s3client = new AmazonS3Client(configuration);

    if (!awsAccessKey.trim().equals("") && !awsSecretKey.trim().equals("")) {
      logInfo("Read S3 credentials", true);
      s3client = new AmazonS3Client(new BasicAWSCredentials(awsAccessKey, awsSecretKey), configuration);
      sc.conf().set("fs.s3a.access.key", awsAccessKey);
      sc.conf().set("fs.s3a.secret.key", awsSecretKey);
    }

    logInfo("Start process", true);
    for (String schema_folder : schema_folders) {
      logInfo("Start to load schema folder " + schema_folder, true);
      post2Tg(s3client, sc, schema_folder, target_host, target_path, rest_endpoint, times, tg_token);
      logInfo("Loding schema folder " + schema_folder + " successfully", true);
    }
    logInfo("End process", true);
  }



  /*
    transfer parquet file to csv.
    param:  1. s3client: s3 client used to read from S3
            2. sc: spark sql instance
            3. bucket_name: S3 bucket name
            4. schema_folder: folder path in S3 bucket
  */

  private void post2Tg(AmazonS3Client s3client, SparkSession sc, String schema_folder,
                       String target_host, String target_path, String rest_endpoint, int times, String tg_token) {

    // get date folder. folder is named by datetime, like 20160402_20171029_20171102T170615.937Z
    ListObjectsRequest listFolders = new ListObjectsRequest()
            .withBucketName(bucket_name).withPrefix(schema_folder + "/").withDelimiter("/");
    ObjectListing foldersListing;
    do {
      foldersListing = s3client.listObjects(listFolders);
      List<String> folder_path_arr = foldersListing.getCommonPrefixes();
      // if no folder under the schema_folder, then just read files
      if (folder_path_arr.size() == 0) {
        if (readFileOnly) {
          folder_path_arr.add(schema_folder);
        } else {
          folder_path_arr.add(schema_folder + "/");
        }
      }
      for (String folder_path : folder_path_arr) {
        logInfo("Start to load date folder " + folder_path, true);

        // collect all parquet file paths
        List<String> s3Paths = new ArrayList<String>();

        // get all parquet files in the date folder and add to s3Paths
        ListObjectsRequest listObjectRequest = new ListObjectsRequest()
                .withBucketName(bucket_name).withPrefix(folder_path);
        ObjectListing objectListing;
        do {
          objectListing = s3client.listObjects(listObjectRequest);
          for (S3ObjectSummary s3Object : objectListing.getObjectSummaries()) {
            if (s3Object.getKey().endsWith(".parquet")) {
              String absoluteS3Path = bucket_name + S3FileSeparator + s3Object.getKey();
              s3Paths.add(S3Prefix + absoluteS3Path);
              logInfo("file " + absoluteS3Path);
            }
          }
          // continue geting parquet files from last fetching marker
          listObjectRequest.setMarker(objectListing.getNextMarker());
        } while (objectListing.isTruncated() == true);

        if (s3Paths.size() == 0) {
          System.out.println("Error ! No file found");
          System.exit(1);
        }
        // If it is local, write to csv. If it is schema, print schema only. If it is IP address, do post to TG.
        if (target_host.equals("schema")) {
          Dataset<Row> parquet_data = sc.read().parquet(s3Paths.get(0));
          long count = parquet_data.count();
          logInfo("xxxxxxxxxxxxxxxxx count is " + count + " xxxxxxxxxxxxxxxxx");
          logInfo("xxxxxxxxxxxxxxxxx schema xxxxxxxxxxxxxxxxx");
          parquet_data.printSchema();
          logInfo("xxxxxxxxxxxxxxxxx schema xxxxxxxxxxxxxxxxx");
        } else {
          // parallel read those files
          Dataset<Row> parquet_data = sc.read().parquet(JavaConversions.asScalaBuffer(s3Paths));
          for (int i = 1; i < times; i++) {
            Dataset<Row> tmp_data = sc.read().parquet(JavaConversions.asScalaBuffer(s3Paths));
            tmp_data.withColumn("id", functions.lit("times_" + times + "_" + tmp_data.col("id").toString()));
            parquet_data = parquet_data.union(tmp_data);
          }
          long count = parquet_data.count();
          logInfo("xxxxxxxxxxxxxxxxx count is " + count + " xxxxxxxxxxxxxxxxx");
          logInfo("xxxxxxxxxxxxxxxxx schema xxxxxxxxxxxxxxxxx");
          parquet_data.printSchema();
          logInfo("xxxxxxxxxxxxxxxxx schema xxxxxxxxxxxxxxxxx");

          if (target_host.equals("local")) {
            String localFolder = localPrefix + target_path + "/" + folder_path;
            logInfo("write data to local " + localFolder);
            parquet_data.write().format("csv").mode(SaveMode.Overwrite).save(localFolder);
          } else {
            logInfo("send data to restpp endpoint " + rest_endpoint);
            processHiveData(target_host, rest_endpoint, parquet_data, tg_token);
          }
        }
        logInfo("Load date folder " + folder_path + " successfully", true);
      }
      // continue geting date folder name from last fetching marker
      listFolders.setMarker(foldersListing.getNextMarker());
    } while(foldersListing.isTruncated() == true);

    // JavaRDD<String> rdd = context.parallelize(s3Paths);
    // JavaRDD<String> ops = rdd.map(filePath -> {
    //   Dataset<Row> onefile = new SQLContext(context).read().parquet(S3Prefix + filePath);
    //   // //processHiveData(onefile, "", 0.2f, 1);
    //   onefile.show();
    //   return "done";
    // });
    // String total = ops.reduce((a, b) -> a + b);
  }


  private void processHiveData(String target_host, String rest_endpoint, final Dataset<Row> hiveData, String tg_token) {
    hiveData.foreachPartition(rowIterator -> {
      int rowCount = 0;
      StringBuilder payload = new StringBuilder("");
      while (rowIterator.hasNext()) {
        Row row = rowIterator.next();
        rowCount++;
        for (int i = 0; i < row.length(); i++){
          payload.append(Objects.toString(row.get(i), "") + (i == row.length() - 1 ? "\n" : ","));
        }

        if (rowCount == 5000){
          batchPost(target_host, rest_endpoint, payload, tg_token);
          rowCount = 0;
        }
      }
      //handle case for the last batch
      if (rowCount > 0){
        batchPost(target_host, rest_endpoint, payload, tg_token);
      }
    });
  }

  public void batchPost(String target_host, String rest_endpoint, StringBuilder payload, String tg_token) {
    // String requestUrl = "http://" + host + ":9000/dumpfile?filename=/tmp/data.csv";
    String requestUrl = target_host + "/ddl?sep=,&tag=" + rest_endpoint + "&eol=%0A";
    String response = sendPostRequest(requestUrl, tg_token, payload.toString());
    JSONObject res_json;
    try {
      res_json = new JSONObject(response);
      if (res_json.has("error") && res_json.optBoolean("error", true) == true) {
        logWarning("Response has error: " + response);
      } else {
        logInfo("Response: " + response);
      }
    } catch (JSONException e) {
      e.printStackTrace();
    }
    payload.setLength(0);
  }

  public static String sendPostRequest(String requestUrl, String tg_token, String payload) {
    StringBuffer sb = new StringBuffer();
    try {
      URL url = new URL(requestUrl);
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();

      connection.setDoInput(true);
      connection.setDoOutput(true);
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Accept", "application/json");
      connection.setRequestProperty("Authorization", tg_token);
      connection.setRequestProperty("Content-Type", "application/json; charset = UTF-8");
      OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream(), "UTF-8");
      writer.write(payload);
      writer.close();
      BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()));
      String line = null;
      while ((line = br.readLine()) != null) {
        sb.append(line);
      }
      br.close();
    } catch (Exception e) {
      throw new RuntimeException(e.getMessage());
    }
    return sb.toString();
  }
}
