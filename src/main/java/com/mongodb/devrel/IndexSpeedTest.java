package com.mongodb.devrel;

import static com.mongodb.client.model.Accumulators.sum;
import static com.mongodb.client.model.Aggregates.group;
import static java.util.Arrays.asList;

import java.util.ArrayList;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Projections.*;

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

public class IndexSpeedTest extends BaseMongoTest {
    private long threadNo;
    private Random rng;
    MongoDatabase database;
    MongoCollection<Document> coll_one;


    IndexSpeedTest(MongoClient client, Document config, long threadNo) {
        super(client, config);
        this.threadNo = threadNo;
        this.rng = new Random();
        database = mongoClient.getDatabase(testConfig.getString("database"));
        coll_one = database.getCollection("one");
    }

    private static final Logger logger = LoggerFactory.getLogger(IndexSpeedTest.class);

    @Override
    public void run() {
        // logger.info("Starting test.");
        int nDocs = testConfig.getInteger("records");
        int nTests = testConfig.getInteger("calls");
        int nThreads = testConfig.getInteger("threads");
        String testMode = testConfig.getString("mode");

        int nOps = nTests / nThreads;
        for (int o = 0; o < nOps; o++) {
            int id = rng.nextInt(nDocs);
            Bson query = eq(testMode, id); // Super simple one for testMode
            Bson projection = include("lpl");
            Document r = coll_one.find(query).projection(projection).first();
            if (this.threadNo == 0 && o == 0) {
                logger.info("Testing " + testMode + " " + nTests + " calls like " + query.toBsonDocument().toJson());
                logger.info("Data Return Size (MB) : " + ((long)((long)r.toJson().length() * (long)nTests) / (1024*1024)));
            }
        }

       

    }

    public void GenerateData() {

        int nDocs = testConfig.getInteger("records");
        int payloadbytes = testConfig.getInteger("payLoadBytes", 4096);

        Random rng = new Random();
        long docCount = coll_one.estimatedDocumentCount();
        if (docCount > 0) {
            logger.info("Sample data already exists");
            return;
        }
        logger.info("Generating sample data");
        List<Document> docs = new ArrayList<Document>();
        for (int id = 1; id <= nDocs; id++) {
            Document d = new Document();

            d.put("_id", id);
            d.put("key", id);
            d.put("mkey", asList(id));
            d.put("spl", "Small Payload");
            byte[] byteArray = new byte[payloadbytes];
            rng.nextBytes(byteArray);
            Binary largePayload = new Binary(byteArray);
            d.put("lpl", largePayload);

            docs.add(d);
            if (docs.size() == 1000) {
                logger.info("Added " + id);
                coll_one.insertMany(docs);
                docs = new ArrayList<Document>();
            }
        }
        // Create
        logger.info("Building Indexes");
        coll_one.createIndex(new Document("key", 1));
        coll_one.createIndex(new Document("mkey", 1));
    }

    

    public void WarmCache() {
        logger.info("Warming Cache");

        // Has to check every document for this non existent field
        Bson touch = group("$nonexistent", sum("count", 1));
        Document rval = coll_one.aggregate(asList(touch)).first();
        logger.info("Warming Done " + rval.toJson());
    }
}
