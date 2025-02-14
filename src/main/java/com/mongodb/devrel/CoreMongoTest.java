package com.mongodb.devrel;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoreMongoTest {
  private static final Logger logger = LoggerFactory.getLogger(CoreMongoTest.class);

  public static void main(String[] args) {

    if (args.length < 1) {
      System.out.println("Usage: java -jar CoreMongoTest.jar <config.json>");
      return;
    }
    LogManager.getLogManager().reset();
    Document testConfig;

    try {
      String configString = new String(Files.readAllBytes(Paths.get(args[0])));
      testConfig = Document.parse(configString);
    } catch (Exception ex) {
      logger.error("ERROR IN CONFIG{}", ex.getMessage());
      return;
    }

    logger.info(testConfig.toJson());

    logger.info("Connecting to MongoDB...");
    MongoClient mongoClient = null;

    try {
      String mongoURI = System.getenv("MONGO_URI");
      if (mongoURI == null) {
        logger.error("MONGO_URI not defined");
        System.exit(1);
      }
      logger.info(mongoURI);
      mongoClient = MongoClients.create(mongoURI);
      Document rval = mongoClient.getDatabase("admin").runCommand(new Document("ping", 1));
      logger.info(rval.toJson());
    } catch (Exception e) {

      logger.error("An error occurred while connecting to MongoDB", e);
    }

    try {

      @SuppressWarnings("rawtypes")
      Class testClass = Class.forName(testConfig.getString("testName"));
      BaseMongoTest test =
          (BaseMongoTest)
              testClass.getDeclaredConstructors()[0].newInstance(mongoClient, testConfig, 0);

      test.GenerateData();
      test.WarmCache();

      int numberOfThreads = testConfig.getInteger("threads", 20);

      for (String testMode : testConfig.getList("testModes", String.class)) {
        logger.info("Reset");
        test.TestReset();
        testConfig.put("mode", testMode);

        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        if (testConfig.getBoolean("warmup", true)) {
          logger.info("Test Warmup Run");

          testConfig.put("warmup", true);
          runTestsInParallel(testConfig, mongoClient, testClass, numberOfThreads, executorService);
        }

        testConfig.put("warmup", false);
        Document statusBefore;
        Document statusAfter;

        assert mongoClient != null;
        statusBefore = mongoClient.getDatabase("admin").runCommand(new Document("serverStatus", 1));

        executorService = Executors.newFixedThreadPool(numberOfThreads);
        logger.info("Test Live Run {} threads", numberOfThreads);
        Date startTime = new Date();
        runTestsInParallel(testConfig, mongoClient, testClass, numberOfThreads, executorService);
        Date endTime = new Date();
        long timeTaken = endTime.getTime() - startTime.getTime();
        long opsPerSecond = (testConfig.getInteger("calls") * 1000) / timeTaken;

        statusAfter = mongoClient.getDatabase("admin").runCommand(new Document("serverStatus", 1));
        logger.info("Test Complete");
        long cb;
        long ca;

        if (statusBefore.getString("process").equals("mongod")) {
          // this code only works for replica sets not sharded clusters
          try {
            // Work round the fact this type changes!
            cb =
                (long)
                    statusBefore
                        .get("wiredTiger", new Document())
                        .get("cache", new Document())
                        .getInteger("bytes read into cache");
          } catch (Exception e) {
            cb =
                statusBefore
                    .get("wiredTiger", new Document())
                    .get("cache", new Document())
                    .getLong("bytes read into cache");
          }

          try {
            ca =
                (long)
                    statusAfter
                        .get("wiredTiger", new Document())
                        .get("cache", new Document())
                        .getInteger("bytes read into cache");
          } catch (Exception e) {
            ca =
                statusAfter
                    .get("wiredTiger", new Document())
                    .get("cache", new Document())
                    .getLong("bytes read into cache");
          }

          logger.info("MB Read Into Cache during test: {}", (ca - cb) / (1024 * 1024));
        }
        logger.info("Time: {} s {} ops/s", timeTaken / 1000, opsPerSecond);
      }

    } catch (Exception e) {

      logger.error("An error occurred: {}", e.getMessage());
      e.printStackTrace();
    }
  }

  private static void runTestsInParallel(
      Document testConfig,
      MongoClient mongoClient,
      Class testClass,
      int numberOfThreads,
      ExecutorService executorService)
      throws InstantiationException,
          IllegalAccessException,
          java.lang.reflect.InvocationTargetException,
          InterruptedException {

    for (int threadNo = 0; threadNo < numberOfThreads; threadNo++) {
      BaseMongoTest t =
          (BaseMongoTest)
              testClass.getDeclaredConstructors()[0].newInstance(mongoClient, testConfig, threadNo);

      executorService.submit(t);
    }
    executorService.shutdown();
    executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
  }
}
