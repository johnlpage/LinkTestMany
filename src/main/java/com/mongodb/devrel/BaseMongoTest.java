package com.mongodb.devrel;

import com.mongodb.client.MongoClient;
import org.bson.Document;

public class BaseMongoTest implements Runnable {
  protected MongoClient mongoClient;
  protected Document testConfig;

  BaseMongoTest(MongoClient client, Document config) {
    this.mongoClient = client;
    this.testConfig = config;
  }

  public void GenerateData() {
    throw new UnsupportedOperationException("Unimplemented method 'GenerateData'");
  }

  public void WarmCache() {
  }

  public void TestReset() {
  }

  @Override
  public void run() {
    throw new UnsupportedOperationException("Unimplemented method 'run'");
  }
}
