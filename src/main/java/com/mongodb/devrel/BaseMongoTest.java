package com.mongodb.devrel;

import org.bson.Document;

import com.mongodb.client.MongoClient;

public class BaseMongoTest implements Runnable {
    protected MongoClient mongoClient;
    protected Document testConfig;
    
    BaseMongoTest(MongoClient client, Document config ) {
        this.mongoClient = client;
        this.testConfig = config;
    }

    public   void GenerateData() {

    }

    public  void WarmCache() {

    }

    @Override
    public void run() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'run'");
    }
}
