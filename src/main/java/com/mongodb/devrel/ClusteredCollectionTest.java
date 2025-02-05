package com.mongodb.devrel;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.commons.lang3.RandomStringUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusteredCollectionTest extends BaseMongoTest {
  private static final Logger logger = LoggerFactory.getLogger(ClusteredCollectionTest.class);

  MongoDatabase database;
  MongoCollection<Document> base_pricing;
  MongoCollection<Document> pricing;
  MongoCollection<Document> clustered_pricing;
  Random classRng = new Random();


  Document cardinality =
          new Document()
                  .append("categoryCode", 10)
                  .append("orgUnit", 4)
                  .append("market", 5)
                  .append("bookingChannel", 3)
                  .append("bookingType", 3)
                  .append("sellingType", 3)
                  .append("currencyCode", 3)
                  .append("packageCode", 3)
                  .append("brand", 3);



  ClusteredCollectionTest(MongoClient client, Document config, long threadNo) {
    super(client, config);
    database = mongoClient.getDatabase(testConfig.getString("database"));
    pricing = database.getCollection(testConfig.getString("collection"));
    base_pricing = database.getCollection(testConfig.getString("refcolname"));
    clustered_pricing = database.getCollection(testConfig.getString("collection") + "_c");
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
    int meanVariantsPerBP = testConfig.getInteger("meanVariantsPerBP");

    int nBasePrices = nCruises * BPPerCruise;


    MongoCollection<Document> testCollection;
    if (testConfig.getString("mode").equals("clustered")) {
      testCollection = clustered_pricing;
    } else {
      testCollection = pricing;
    }
    Random rng = new Random();

    // For all the categorical fields get all the values
    for (int op = 0; op < nOps ; op++) {

      int bpid = rng.nextInt(nBasePrices);

      int CruiseCode = bpid % nCruises; // Last bits
      int basePriceCode = (bpid / nCruises) % BPPerCruise;


      Document query = new Document();
      query.put("cruiseCode", "CRS" + CruiseCode);
      query.put("basePriceUUID", "CRS" + CruiseCode + "_BP" + basePriceCode);
      Document BasePriceDoc = base_pricing.find(query).first();


      rng.setSeed(bpid);
      int nSellingPrices = rng.nextInt(meanVariantsPerBP * 2);
      for (int sp = 0; sp < nSellingPrices; sp++) {
        Document vals = generateRecord(bpid,sp, rng);
        //This shoudl have generated a document with all the fields we need and a few others


        Bson updateSellingPriceKey = and(eq("cruiseCode", vals.get("cruiseCode")),
                eq("categoryCode",vals.get("categoryCode")),
                eq("basePriceUUID", vals.get("basePriceUUID")),
                eq("orgUnit", vals.get("orgUnit")),
                eq("market",vals.get("market")),
                eq("bookingChannel",vals.get("bookingChannel")),
                eq("bookingType",vals.get("bookingType")),
                eq("sellingType", vals.get("sellingType")),
                eq("currencyCode", vals.get("currencyCode")),
                eq("packageCode",vals.get("packageCode")),
                eq("brand", vals.get("brand")));

        //logger.info(updateSellingPriceKey.toBsonDocument().toJson());
        Bson updateSellingPrice = combine(set("lastModified", Date.from(Instant.now())),
                set("deleted", false),
                set("inventoryExhausted", rng.nextBoolean()),
                set("effectiveStartDate",Instant.now()),
                set("effectiveEndDate", Instant.now().plus(30, ChronoUnit.DAYS)));
                set("sellingPriceInfo", vals.get("sellingPriceInfo"));

          UpdateResult a = testCollection.updateOne(updateSellingPriceKey, updateSellingPrice);
        //  logger.info(a.toString());
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
    List<String> testModes = testConfig.getList("testModes", String.class);

    Document updatingIndex =
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

    Document multiKeyShoppingIndex =
        new Document()
            .append("orgUnit", 1)
            .append("market", 1)
            .append("bookingChannel", 1)
            .append("currencyInfo.sellingCurrencies", 1)
            .append("price", 1)
            .append("lastUpdateDate", 1);

    pricing.createIndex(updatingIndex);
    pricing.createIndex(multiKeyShoppingIndex);

    Document basePriceIndex =
            new Document()
                    .append("cruiseCode", 1)
                    .append("basePriceUUID", 1);

    base_pricing.createIndex(basePriceIndex);

    if (testModes.contains("clustered")) {
      String cname = testConfig.getString("collection") + "_c";
      Document createCommand =
          new Document("create", cname)
              .append(
                  "clusteredIndex",
                  new Document("key", new Document("_id", 1)).append("unique", true));
      // Run the command
      database.runCommand(createCommand);

      clustered_pricing.createIndex(updatingIndex);
      clustered_pricing.createIndex(multiKeyShoppingIndex);
    }

    int nCruises = testConfig.getInteger("nCruises");
    int BPPerCruise = testConfig.getInteger("BPPerCruise");
    int VariantsPerBP = testConfig.getInteger("meanVariantsPerBP");

    int nDocs = nCruises * VariantsPerBP * BPPerCruise;


    int[] docIds = new int[nDocs];
    for (int o = 0; o < nDocs; o++) {
      docIds[o] = o;
    }
    shuffleArray(docIds);

    CreateSampleDate(pricing, docIds);
    if (testModes.contains("clustered")) {CreateSampleDate(clustered_pricing, docIds);}
  }

  private void CreateSampleDate(MongoCollection<Document> collection, int[] docIds) {

    int nCruises = testConfig.getInteger("nCruises");
    int BPPerCruise = testConfig.getInteger("BPPerCruise");
    int meanVariantsPerBP = testConfig.getInteger("meanVariantsPerBP");

    int nBasePrices = nCruises * BPPerCruise;
    logger.info("Loading " + nBasePrices + " Base Prices into " + pricing.getNamespace());

    List<Document> toAdd = new ArrayList<>();
    Random rng = new Random(); // Seeded RNG on base price
    for (int bp = 0; bp < nBasePrices; bp++) {
      int bpid = docIds[bp]; // Build randomly
      rng.setSeed(bpid);
      int nSellingPrices = rng.nextInt(meanVariantsPerBP * 2);

      // Create a base price record
      Document bprecord = generateRecord(bpid,1_000_000, rng);
      base_pricing.insertOne(bprecord);

      for (int sp = 0; sp < nSellingPrices; sp++) {
        Document record = generateRecord(bpid,sp, rng);

        toAdd.add(record);
        if (toAdd.size() >= 1000) {
          collection.insertMany(toAdd);
          toAdd.clear();
        }
      }
    }
    if (!toAdd.isEmpty()) {
      collection.insertMany(toAdd);
    }
  }

  Document generateRecord(int bpid, int variant, Random rng) {
    int nCruises = testConfig.getInteger("nCruises");
    int BPPerCruise = testConfig.getInteger("BPPerCruise");
    int CruiseCode = bpid % nCruises; // Last bits
    int basePriceCode = (bpid / nCruises) % BPPerCruise;

    rng.setSeed((bpid* 10000L)+variant);
    Document record = new Document();

    // TODO
    record.put("_id", new ObjectId());
    record.put("cruiseCode", "CRS" + CruiseCode);
    record.put("basePriceUUID", "CRS" + CruiseCode + "_BP" + basePriceCode);

    StringBuilder sellingPriceUUID = new StringBuilder( "CRS" + CruiseCode + "_BP" + basePriceCode);

    for (Map.Entry<String, Object> entry : cardinality.entrySet()) {
      String key = entry.getKey();
      Integer value = (Integer) entry.getValue();
      StringBuilder valuepfxsb = new StringBuilder(key.substring(0,1).toUpperCase());
      for (char c : key.toCharArray()) {
        if (Character.isUpperCase(c)) {
          valuepfxsb.append(c);
        }
      }
      valuepfxsb.append(rng.nextInt(100,100+value));
      record.put(key, valuepfxsb.toString());
      sellingPriceUUID.append("_");
      sellingPriceUUID.append(valuepfxsb);
    }

    record.put("sellingPriceUUID", sellingPriceUUID.toString());

    // From here on down is actually random so we can edit it

    record.put(
        "effectiveEndDate", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    record.put("ratePlanClassification", getRandomElement(Arrays.asList("ALL", "VIP", "STANDARD") ));
    record.put("ratePlanCode", RandomStringUtils.randomAlphanumeric(10).toUpperCase());

    // Nested arrays with price into
    List<Map<String, Object>> sellingPriceInfo = new ArrayList<>();
    for (int spi = 0; spi < classRng.nextInt(8) + 1; spi++) {
      Map<String, Object> priceInfo = new HashMap<>();
      priceInfo.put("occupancy", getRandomElement(Arrays.asList("1A", "2B", "3C") ));
      priceInfo.put("basePrice", classRng.nextInt(500, 1500));
      priceInfo.put("NCCF", classRng.nextInt(50, 150));
      priceInfo.put("discount", classRng.nextInt(100, 500));
      priceInfo.put(
          "lastmodifiedTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
      priceInfo.put(
          "promotionCombined", getRandomSample(Arrays.asList("P1", "P2", "P3", "P4", "P5"), 3));
      priceInfo.put("LAF", classRng.nextInt(800, 1200));
      List<Map<String, Object>> splitUp = new ArrayList<>();
      for (int su = 0; su < classRng.nextInt(10) + 1; su++) {
        Map<String, Object> splitUpEntry = new HashMap<>();
        splitUpEntry.put("chargeType", getRandomElement(Arrays.asList("CAB", "EXTRA", "TAX")));
        splitUpEntry.put("PassengerIdentifier", String.valueOf(classRng.nextInt(5) + 1));
        splitUpEntry.put("passengerType", getRandomElement(Arrays.asList("A", "C", "S")));
        splitUpEntry.put("discount", classRng.nextInt(50, 200));
        splitUpEntry.put("basePrice", classRng.nextInt(700, 1500));
        splitUp.add(splitUpEntry);
      }
      priceInfo.put("splitUp", splitUp);
      sellingPriceInfo.add(priceInfo);
    }

    record.put("sellingPriceInfo", sellingPriceInfo);

    record.put("deleted", classRng.nextBoolean());

    record.put("ratePlanTypeCode", getRandomElement(Arrays.asList("ALL", "CIT", "FIT")));
    record.put(
        "effectiveStartDate", Instant.now());
    record.put(
            "effectiveEndDate", Instant.now().plus(30, ChronoUnit.DAYS));
    Map<String, Object> currencyInfo = new HashMap<>();
    currencyInfo.put("baseCurrency", getRandomElement(Arrays.asList("USD", "EUR", "INR", "GBP")));
    currencyInfo.put("exchangeRateCode", getRandomElement(Arrays.asList("EX1", "EX2")));
    currencyInfo.put(
        "sellingCurrencies", getRandomSample(Arrays.asList("USD", "EUR", "INR", "GBP", "CAD"), 3));

    record.put("currencyInfo", currencyInfo);
    record.put("inventoryExhausted", classRng.nextBoolean());
    record.put("lastModified", Instant.now());

    return record;
  }

   List<String> getRandomSample(List<String> list, int sampleSize) {

    // Create a copy of the list to avoid modifying the original
    List<String> copy = new ArrayList<>(list);

    // Shuffle the copy to randomize the order of elements
    Collections.shuffle(copy,classRng);
    // Return the first 'sampleSize' elements
    return copy.subList(0, sampleSize);
  }
  String getRandomElement(List<String> list) {

    int randomIndex = classRng.nextInt(list.size());
    return list.get(randomIndex);
  }

  public void WarmCache() {
    logger.info("No Cache Warm up Required");
  }
}
