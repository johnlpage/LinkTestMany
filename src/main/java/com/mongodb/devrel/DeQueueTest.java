package com.mongodb.devrel;

import static com.mongodb.client.model.Accumulators.sum;
import static com.mongodb.client.model.Aggregates.group;
import static java.util.Arrays.asList;

import java.util.ArrayList;

import static com.mongodb.client.model.Filters.*;

import static com.mongodb.client.model.Updates.*;

import java.util.List;
import java.util.Random;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;

// This test simulates Multiple Consumer threads trying to edit the same documents
// We start with N documents each of which needs updated independently
// Generating a lot of writeConflicts

public class DeQueueTest extends BaseMongoTest {
    private long threadNo;
    MongoDatabase database;
    MongoCollection<Document> coll_one;
    Random rng;

    DeQueueTest(MongoClient client, Document config, long threadNo) {
        super(client, config);
        this.threadNo = threadNo;
        this.rng = new Random();
        database = mongoClient.getDatabase(testConfig.getString("database"));
        coll_one = database.getCollection("queue");
    }

    private static final Logger logger = LoggerFactory.getLogger(DeQueueTest.class);

    @Override
    public void run() {
        // No warmup run in this one
        if (testConfig.getBoolean("warmup", false)) {
            return;
        }
        // Run until we don't find any
        Bson findNew = eq("state", "New");

        Double skipRatio = testConfig.getDouble("skipRatio");
        if (skipRatio == null) {
            skipRatio = 0.1;
        }
        Document randExpression = new Document("$rand", new Document());
        Document skipRandom = new Document("$gt", asList(skipRatio, randExpression));

        String testMode = testConfig.getString("mode");
        if (testMode.equals("expr")) {
            findNew = and(findNew, expr(skipRandom));
        }

        //In this one the query has the form
        //  {{$or: [ {state:"New", subq : { $gt: x }}, {state:"New", subq: {$lte:x}} ]

         if(testMode.equals("ranged")){
            int pivot = rng.nextInt(100); // Or evn use threadid modulo X
            Bson morequery = and(findNew,gt("subq",pivot));
            Bson lessquery = and(findNew,lte("subq",pivot));
            findNew = or(morequery,lessquery);
        }
        Bson claim = set("state", "InProgress");
        // Need to get the doc back
        int retries = 10; // How many times to not find anythign before declaring done
        if (threadNo == 0) {
            logger.info(findNew.toBsonDocument().toJson());
            logger.info("Skip Ratio = " + skipRatio.toString());
        }

        while (retries > 0) {
            Document rval = coll_one.findOneAndUpdate(findNew, claim);
            if (rval == null) {
                retries--;
            }

        }

    }

    public void GenerateData() {
        // WE NEED TO REGENRATE PER TEST MODE
        // THIS IS ONLY CALLED ONECE

    }

    public void WarmCache() {
    }

    public void TestReset() {
        logger.info("Regenerating data");
        int nDocs = testConfig.getInteger("records");
        int payloadbytes = testConfig.getInteger("payLoadBytes", 4096);
        List<Document> docs = new ArrayList<Document>();

        Random rng = new Random();
        try {
            coll_one.drop();
        } catch (Exception ex) {
            logger.info(ex.getMessage());
        }
        logger.info("Populating Queue with " + nDocs + " documents.");
        for (int id = 1; id <= nDocs; id++) {
            Document d = new Document();

            d.put("_id", id);
            d.put("state", "New");
            d.put("subq",rng.nextInt(100));

            byte[] byteArray = new byte[payloadbytes];
            rng.nextBytes(byteArray);
            Binary largePayload = new Binary(byteArray);
            d.put("payload", largePayload);
            docs.add(d);
            if (docs.size() == 1000) {
                // logger.info("Added " + id);
                coll_one.insertMany(docs);
                docs = new ArrayList<Document>();
            }
        }
        if (docs.size() > 0) {
            coll_one.insertMany(docs);
            docs = new ArrayList<Document>();
        }
        // Create
        logger.info("Building Indexes");

        coll_one.createIndex(new Document("subq", 1).append("state",1));

    }

}
