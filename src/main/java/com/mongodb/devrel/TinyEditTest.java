package com.mongodb.devrel;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TinyEditTest extends BaseMongoTest {
  private static final Logger logger = LoggerFactory.getLogger(TinyEditTest.class);
  MongoDatabase database;
  MongoCollection<Document> collection;
  long threadNo;
  String ipHex;

  TinyEditTest(MongoClient client, Document config, long threadNo) {
    super(client, config);
    database = mongoClient.getDatabase(testConfig.getString("database"));
    collection = database.getCollection(testConfig.getString("collection"));
    this.threadNo = threadNo;
  }

  public void run() {
    try {
      InetAddress localHost = InetAddress.getLocalHost();
      // Convert the byte array to an integer
      int intRepresentation = 0;
      for (byte b : localHost.getAddress()) {
        intRepresentation = (intRepresentation << 8) | (b & 0xFF);
      }
      // Format the integer as an 8-character hexadecimal string
      ipHex = String.format("%08X", intRepresentation);
      // logger.info("IP address: " + ipHex + " for thread: " + threadNo);
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
    int nTests = testConfig.getInteger("calls");
    int nThreads = testConfig.getInteger("threads");
    int docsPerThread = testConfig.getInteger("docsPerThread");

    nTests = nTests / nThreads;
    String recordId;

    Bson update = Updates.inc("count", 1);
    // Create UpdateOptions with upsert set to trueÎÍ
    UpdateOptions options = new UpdateOptions().upsert(true);
    for (int test = 0; test < nTests; test++) {
      for (int doc = 0; doc < docsPerThread; doc++) {
        recordId = ipHex + "_" + threadNo + "_" + doc;
        Bson filter = Filters.eq("_id", recordId);
        collection.updateOne(filter, update, options);
      }
    }
  }

  public void GenerateData() {
    logger.info("No data generation required");
  }

  public void WarmCache() {
    logger.info("No cache warm up was required");
  }
}
