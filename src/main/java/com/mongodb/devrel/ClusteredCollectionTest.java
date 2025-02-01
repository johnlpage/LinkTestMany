package com.mongodb.devrel;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.apache.commons.lang3.RandomStringUtils;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusteredCollectionTest extends BaseMongoTest {
  private static final Logger logger = LoggerFactory.getLogger(ClusteredCollectionTest.class);
  private static final Random random = new Random();
  MongoDatabase database;
  MongoCollection<Document> pricing;
  MongoCollection<Document> clustered_pricing;

  private long threadNo;

  ClusteredCollectionTest(MongoClient client, Document config, long threadNo) {
    super(client, config);
    this.threadNo = threadNo;
    database = mongoClient.getDatabase(testConfig.getString("database"));
    pricing = database.getCollection(testConfig.getString("collection"));
    clustered_pricing = database.getCollection(testConfig.getString("collection") + "_c");
  }

  private static <T> T getRandomElement(List<T> list) {
    return list.get(random.nextInt(list.size()));
  }

  private static List<String> getRandomSample(List<String> list, int size) {
    List<String> shuffled = new ArrayList<>(list);
    Collections.shuffle(shuffled);
    return shuffled.subList(0, size);
  }

  private static int getRandomInt(int min, int max) {
    return random.nextInt((max - min) + 1) + min;
  }

  public static void shuffleArray(int[] array) {
    Random random = new Random(); // You can also seed it for reproducibility
    for (int i = array.length - 1; i > 0; i--) {
      // Generate a random index from 0 to i
      int j = random.nextInt(i + 1);

      // Swap elements at index i and j
      int temp = array[i];
      array[i] = array[j];
      array[j] = temp;
    }
  }

  @Override
  public void run() {

    int nTests = testConfig.getInteger("calls");
    int nThreads = testConfig.getInteger("threads");

    int nOps = nTests / nThreads;
    int nCruises = testConfig.getInteger("nCruises");
    int BPPerCruise = testConfig.getInteger("BPPerCruise");
    int VariantsPerBP = testConfig.getInteger("BPPerCruise");

    int nDocs = nCruises * VariantsPerBP * BPPerCruise;
    MongoCollection<Document> testCollection;
    if(testConfig.getString("mode").equals("clustered")) {
      testCollection = clustered_pricing;
     } else {
      testCollection = pricing;
    }

    // For all the categorical fields get all the values
    for (int o = 0; o < nOps / VariantsPerBP; o++) {

      // Choose a random Base price then update all the derived prices
      int CruiseCode = getRandomInt(0, nCruises);
      int basePriceCode = getRandomInt(0, BPPerCruise);

      for (int variant = 0; variant < VariantsPerBP; variant++) {
        Document query = new Document();
        query.put("basePriceUUID", "C" + CruiseCode + "B" + basePriceCode);
        query.put("variant", variant);

        testCollection.updateOne(
            query,
            new Document(
                "$set",
                new Document("price", random.nextInt(1000)).append("lastUpdateDate", new Date())));
      }
    }
  }

  public void GenerateData() {


    long docCount = pricing.estimatedDocumentCount();
    if (docCount > 0) {
      logger.info("Sample data already exists");
      return;
    }

    // Realtively large secondary indexes, one is updated frequently
    // Actually this one is used to query so will use significant RAM.

    Document index1 =
        new Document()
            .append("cruiseCode", 1)
            .append("categoryCode", 1)
            .append("basePriceUUID", 1)
            .append("orgUnit", 1)
            .append("market", 1)
            .append("bookingChannel", 1)
            .append("bookingType", 1)
            .append("sellingType", 1)
            .append("currencyCode", 1)
            .append("packageCode", 1)
            .append("brand", 1);

    Document index2 =
        new Document()
            .append("orgUnit", 1)
            .append("market", 1)
            .append("bookingChannel", 1)
            .append("currencyInfo.sellingCurrencies", 1)
            .append("price", 1)
            .append("lastUpdateDate", 1);

    // Index we edit using
    Document index3 = new Document().append("basePriceUUID", 1).append("variant", 1);

    pricing.createIndex(index1);
    pricing.createIndex(index2);
    pricing.createIndex(index3);

    String cname = testConfig.getString("collection") + "_c";
    Document createCommand =
        new Document("create", cname)
            .append(
                "clusteredIndex",
                new Document("key", new Document("_id", 1)).append("unique", true));
    // Run the command
    database.runCommand(createCommand);

    clustered_pricing.createIndex(index1);
    clustered_pricing.createIndex(index2);
    clustered_pricing.createIndex(index3);
    int nCruises = testConfig.getInteger("nCruises");
    int BPPerCruise = testConfig.getInteger("BPPerCruise");
    int VariantsPerBP = testConfig.getInteger("VariantsPerBP");

    int nDocs = nCruises * VariantsPerBP * BPPerCruise;

    List<Document> toAdd = new ArrayList<>();
    int[] docIds = new int[nDocs];
    for (int o = 0; o < nDocs; o++) {
      docIds[o] = o;
    }
    shuffleArray(docIds);

    CreateSampleDate(pricing,docIds);
    CreateSampleDate(clustered_pricing,docIds);
  }

  private void CreateSampleDate(MongoCollection<Document> collection, int[] docIds) {

    int nCruises = testConfig.getInteger("nCruises");
    int BPPerCruise = testConfig.getInteger("BPPerCruise");
    int VariantsPerBP = testConfig.getInteger("VariantsPerBP");

    int nDocs = nCruises * VariantsPerBP * BPPerCruise;
    logger.info("Loading " + nDocs + " Docs into" + pricing.getNamespace());

    List<Document> toAdd = new ArrayList<>();
    for (int o = 0; o < nDocs; o++) {
      // Pick a price code
      Document record = generateRecord();

      Integer id = docIds[o];
      Integer CruiseCode = id % nCruises; // Last bits
      Integer basePriceCode = (id / nCruises) % BPPerCruise;
      Integer variant = (id / (nCruises * BPPerCruise) % VariantsPerBP);
      record.put("_id", "C" + CruiseCode + "B" + basePriceCode + "V" + variant);
      record.put("CruiseCode", "C" + CruiseCode );
      record.put("basePriceUUID", "C" + CruiseCode + "B" + basePriceCode);
      record.put("variant", variant);

      toAdd.add(record);

      // Iterate over all records updatin gthe price and date
      if (toAdd.size() >= 1000) {
        collection.insertMany(toAdd);

        toAdd.clear();
      }
    }
    if (toAdd.size() > 0) {
      collection.insertMany(toAdd);
    }
  }

  Document generateRecord() {
    Document record = new Document();
    record.put("_id", new ObjectId());
    record.put("sellingType", getRandomElement(Arrays.asList("FIT", "DFIT", "CIT")));
    record.put(
        "effectiveEndDate", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    record.put("orgUnit", getRandomElement(Arrays.asList("USA", "CAN", "EU", "APAC")));
    record.put("ratePlanClassification", getRandomElement(Arrays.asList("ALL", "VIP", "STANDARD")));
    record.put("categoryCode", "CAT" + getRandomInt(10, 99));
    record.put("ratePlanCode", RandomStringUtils.randomAlphanumeric(10).toUpperCase());
    record.put("sellingPriceUUID", UUID.randomUUID().toString());
    record.put("market", getRandomElement(Arrays.asList("CAN", "USA", "EU", "IND")));
    List<Map<String, Object>> sellingPriceInfo = new ArrayList<>();
    Map<String, Object> priceInfo = new HashMap<>();
    priceInfo.put("occupancy", getRandomElement(Arrays.asList("1A", "2B", "3C")));
    priceInfo.put("basePrice", getRandomInt(500, 1500));
    priceInfo.put("NCCF", getRandomInt(50, 150));
    priceInfo.put("discount", getRandomInt(100, 500));
    priceInfo.put(
        "lastmodifiedTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    priceInfo.put(
        "promotionCombined", getRandomSample(Arrays.asList("P1", "P2", "P3", "P4", "P5"), 3));
    priceInfo.put("LAF", getRandomInt(800, 1200));
    List<Map<String, Object>> splitUp = new ArrayList<>();
    Map<String, Object> splitUpEntry = new HashMap<>();
    splitUpEntry.put("chargeType", getRandomElement(Arrays.asList("CAB", "EXTRA", "TAX")));
    splitUpEntry.put("PassengerIdentifier", String.valueOf(random.nextInt(5) + 1));
    splitUpEntry.put("passengerType", getRandomElement(Arrays.asList("A", "C", "S")));
    splitUpEntry.put("discount", getRandomInt(50, 200));
    splitUpEntry.put("basePrice", getRandomInt(700, 1500));
    splitUp.add(splitUpEntry);
    priceInfo.put("splitUp", splitUp);
    sellingPriceInfo.add(priceInfo);
    record.put("sellingPriceInfo", sellingPriceInfo);
    record.put("bookingChannel", getRandomElement(Arrays.asList("B2B", "B2C", "C2C")));
    record.put("deleted", random.nextBoolean());
    record.put("bookingType", getRandomElement(Arrays.asList("IND", "GROUP")));
    record.put("packageCode", RandomStringUtils.randomAlphanumeric(6).toUpperCase());
    record.put("ratePlanTypeCode", getRandomElement(Arrays.asList("ALL", "CIT", "FIT")));
    record.put(
        "effectiveStartDate", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

    Map<String, Object> currencyInfo = new HashMap<>();
    currencyInfo.put("baseCurrency", getRandomElement(Arrays.asList("USD", "EUR", "INR", "GBP")));
    currencyInfo.put("exchangeRateCode", getRandomElement(Arrays.asList("EX1", "EX2")));
    currencyInfo.put(
        "applicableCurrencies",
        getRandomSample(Arrays.asList("USD", "EUR", "INR", "GBP", "CAD"), 3));

    record.put("currencyInfo", currencyInfo);
    record.put("inventoryExhausted", random.nextBoolean());
    record.put("lastModified", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    record.put("currencyCode", getRandomElement(Arrays.asList("USD", "EUR", "INR")));
    record.put("brand", getRandomElement(Arrays.asList("SSC", "MSC", "NOR")));
    // Add a Blob for size
    byte[] byteArray = new byte[3000];
    random.nextBytes(byteArray);
    Binary largePayload = new Binary(byteArray);
    record.put("payload", largePayload);

    return record;
  }

  public void WarmCache() {
    logger.info("No Cache Warm up Required");
  }
}
