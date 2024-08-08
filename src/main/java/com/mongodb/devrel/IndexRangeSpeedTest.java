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

public class IndexRangeSpeedTest extends BaseMongoTest {
    private long threadNo;
    private Random rng;
    MongoDatabase database;
    MongoCollection<Document> coll_one;
    MongoCollection<Document> coll_two;

    IndexRangeSpeedTest(MongoClient client, Document config, long threadNo) {
        super(client, config);
        this.threadNo = threadNo;
        this.rng = new Random();
        database = mongoClient.getDatabase(testConfig.getString("database"));
        coll_one = database.getCollection("one");
        coll_two = database.getCollection("two");
    }

    private static final Logger logger = LoggerFactory.getLogger(IndexRangeSpeedTest.class);

    @Override
    public void run() {

        int nDocs = testConfig.getInteger("records");
        int nTests = testConfig.getInteger("calls");
        int nThreads = testConfig.getInteger("threads");
        String testMode = testConfig.getString("mode");
        int nOps = nTests / nThreads;
        int rangeSize = testConfig.getInteger("rangesize");
        for (int o = 0; o < nOps; o++) {
            int id = rng.nextInt(nDocs-rangeSize);
            Bson query;
            if( testMode.equals("mkey")) {
                Document e = new Document("$gte",id).append("$lt", id+rangeSize);
                query = com.mongodb.client.model.Filters.elemMatch(testMode,e);
            } else {
            query = and(gte(testMode,id),lt(testMode,id+rangeSize)); //Super simple on for testMode
            }
            Bson projection = include("pl");
            ArrayList<Document> target = new ArrayList<Document>();
            coll_one.find(query).projection(projection).into(target);
            if(this.threadNo == 0 && o==0) {
                logger.info("Testing " +testMode + " "  + nOps + " calls like " + query.toBsonDocument().toJson());
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
        List<Integer> linkSizes = asList(1, 5, 10, 25, 50, 100);
        List<Document> docs = new ArrayList<Document>();
        for (int id = 1; id <= nDocs; id++) {
            Document d = new Document();

            d.put("_id", id);
            d.put("key", id);
            d.put("mkey", asList(id));
            d.put("pl", "Small Payload");
            byte[] byteArray = new byte[payloadbytes];
            rng.nextBytes(byteArray);
            Binary payload = new Binary(byteArray);
            d.put("payload", payload);
            for (Integer c : linkSizes) {
                List<Integer> links = new ArrayList<Integer>();
                for (int l = 0; l < c; l++) {
                    links.add(rng.nextInt(1, nDocs));
                }
                d.put("links_" + c, links);
            }
            docs.add(d);
            if (docs.size() == 1000) {
                logger.info("Added " + id);
                coll_one.insertMany(docs);
                coll_two.insertMany(docs);
                docs = new ArrayList<Document>();
            }
        }
        // Create
        logger.info("Building Indexes");
        for (Integer c : linkSizes) {
            coll_one.createIndex(new Document("links_" + c, 1));
            coll_two.createIndex(new Document("links_" + c, 1));
            coll_one.createIndex(new Document("key", 1));
            coll_one.createIndex(new Document("mkey", 1));
            coll_two.createIndex(new Document("key", 1));
            coll_two.createIndex(new Document("mkey", 1));
        }
    }

    public void WarmCache() {
        logger.info("Warming Cache");

        // Has to check every document for this non existent field
        Bson touch = group("$nonexistent", sum("count", 1));
        Document rval = coll_one.aggregate(asList(touch)).first();
        rval = coll_two.aggregate(asList(touch)).first();
        logger.info("Warming Done " + rval.toJson());
    }
}
