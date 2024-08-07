package com.mongodb.devrel;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Aggregates.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Accumulators.*;
import static java.util.Arrays.asList;

public class ManyLinkTest {
    private static final Logger logger = LoggerFactory.getLogger(ManyLinkTest.class);

    private static void GenerateData(MongoCollection<Document> collection, int nDocs, int cardinality,
            int payloadbytes) {
        // Quick count to see if we already have data

        Random rng = new Random();
        long docCount = collection.estimatedDocumentCount();
        if (docCount > 0) {
            logger.info("Sample data already exists");
            return;
        }
        logger.info("Generating sample data");

        List<Document> docs = new ArrayList<Document>();
        for (int id = 1; id <= nDocs; id++) {
            Document d = new Document();

            d.put("_id", id);

            byte[] byteArray = new byte[payloadbytes];
            rng.nextBytes(byteArray);
            Binary payload = new Binary(byteArray);
            d.put("payload", payload);
            List<Integer> links = new ArrayList<Integer>();
            for (int l = 0; l < cardinality; l++) {
                links.add(rng.nextInt(1, nDocs));
            }
            d.put("links", links);
            docs.add(d);

            if (docs.size() == 1000) {
                logger.info("Added " + id);
                collection.insertMany(docs);
                docs = new ArrayList<Document>();
            }
        }
        // Create
        logger.info("Building Index");
        collection.createIndex(new Document("links", 1));
    }

    private static void WarmCache(MongoCollection<Document> collection) {
        logger.info("Warming Cache");
        Bson touch = group("$nonexistent", sum("count", 1));
        Document rval = collection.aggregate(asList(touch)).first();

        logger.info("Warming Done " + rval.toJson());
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java -jar mongo-manymany-speedtest.jar <config.json>");
            return;
        }
        LogManager.getLogManager().reset();
        Document testConfig;

        try {
            String configString = new String(Files.readAllBytes(Paths.get(args[0])));
            testConfig = Document.parse(configString);
        } catch (Exception ex) {
            logger.error("ERROR IN CONFIG" + ex.getMessage());
            return;
        }

        logger.info(testConfig.toJson());

        logger.info("Connecting to MongoDB...");
        try (MongoClient mongoClient = MongoClients.create(testConfig.getString("uri"))) {
            MongoDatabase database = mongoClient.getDatabase(testConfig.getString("database"));
            MongoCollection<Document> collection = database.getCollection(testConfig.getString("collection"),
                    Document.class);
            logger.info("Connected to database: " + testConfig.getString("database"));

            int nDocs = testConfig.getInteger("records");
            int linkCardinality = testConfig.getInteger("cardinality");

            int payloadbytes = testConfig.getInteger("payLoadBytes");
            GenerateData(collection, nDocs, linkCardinality, payloadbytes);

            WarmCache(collection);

            int numberOfThreads = testConfig.getInteger("threads");

            long totalCalls = testConfig.getInteger("calls");
            int callsPerThread = (int) totalCalls / numberOfThreads;

            for (int direction = 0; direction < 3; direction++) {
                // 0 = Simple, 1 = $in

                ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
                logger.info("Starting Timing");
                Date startTime = new Date();
                for (int threadNo = 0; threadNo < numberOfThreads; threadNo++) {
                    executorService.submit(
                            new DatabaseTask(collection, callsPerThread, threadNo == 0, direction,
                                    linkCardinality == 0));
                }
                executorService.shutdown();
                executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                Date endTime = new Date();
                long timeTaken = endTime.getTime() - startTime.getTime();
                logger.info("Direction " + direction);
                logger.info("Time: " + timeTaken + " ms Operations/s = " + (totalCalls * 1000 / timeTaken));
            }

        } catch (Exception e) {

            logger.error("An error occurred while connecting to MongoDB", e);
        }
    }
}

class DatabaseTask implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseTask.class);
    private MongoCollection<Document> collection;
    private int callsPerThread;
    private boolean report;
    private boolean useIn;
    private boolean single;
    private Random rng;
    private int nDocs;
    private boolean noLinks;

    public DatabaseTask(MongoCollection<Document> collection, int callsPerThread, boolean report, int direction, boolean noLinks) {
        this.collection = collection;
        this.callsPerThread = callsPerThread;
        this.report = report;
        this.rng = new Random();
        this.nDocs = Math.toIntExact(collection.estimatedDocumentCount());
        this.useIn = (direction == 1);
        this.single = (direction == 2);
        this.noLinks = noLinks;
    }

    @Override
    public void run() {
        if (report) {
            logger.info("Running database task on thread: " + Thread.currentThread().getName());
            logger.info("Processing " + callsPerThread + " calls");
        }
        for (int op = 0; op < callsPerThread; op++) {
            // Fetch a Document and the Things linked to It Many to Many

            if (single) {
                Bson pickDocs = match(eq("links", rng.nextInt(1, nDocs)));

                ArrayList<Document> target = new ArrayList<Document>();
                collection.aggregate(asList(pickDocs)).into(target);
            } else {
                Bson pickDoc = match(eq("_id", rng.nextInt(1, nDocs)));
                Bson fetchJoined;
                if (useIn) {
                    fetchJoined = lookup(collection.getNamespace().getCollectionName(),
                            "links", "_id", "joined");
                } else {
                    fetchJoined = lookup(collection.getNamespace().getCollectionName(),
                            "_id", "links", "joined");
                }

                Document rval = null;
                if (noLinks) {
                    rval = collection.aggregate(asList(pickDoc)).first();
                } else {
                    rval = collection.aggregate(asList(pickDoc, fetchJoined)).first();
                }
                if (op == 0 && report) {
                    logger.info("[ " + pickDoc.toBsonDocument().toJson() +
                            "," + fetchJoined.toBsonDocument().toJson() + " ]");
                    logger.info(fetchJoined.toString());
                    logger.info("JSON bytes = " + rval.toJson().length());
                }
            }
        }

    }
}
