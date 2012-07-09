/*
 * Copyright Myrrix Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.myrrix.client;

import java.io.File;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.PatternFilenameFilter;
import org.apache.mahout.cf.taste.common.TasteException;
import org.apache.mahout.cf.taste.impl.common.FullRunningAverage;
import org.apache.mahout.cf.taste.impl.common.RunningAverage;
import org.apache.mahout.common.RandomUtils;
import org.apache.mahout.common.iterator.FileLineIterable;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.myrrix.common.NamedThreadFactory;

public final class LoadTest extends AbstractClientTest {

  private static final Logger log = LoggerFactory.getLogger(LoadTest.class);

  private static final int ITERATIONS = 100000;

  @Override
  protected String getTestDataPath() {
    return "testdata/grouplens10M";
  }

  @Override
  protected boolean useSecurity() {
    return true;
  }

  @Test
  public void testLoad() throws Exception {

    Set<Long> userIDsSet = Sets.newHashSet();
    Set<Long> itemIDsSet = Sets.newHashSet();
    Splitter comma = Splitter.on(',');
    for (File f : getTestTempDir().listFiles(new PatternFilenameFilter(".+\\.csv(\\.(zip|gz))?"))) {
      for (CharSequence line : new FileLineIterable(f)) {
        Iterator<String> it = comma.split(line).iterator();
        userIDsSet.add(Long.parseLong(it.next()));
        itemIDsSet.add(Long.parseLong(it.next()));
      }
    }
    List<Long> uniqueUserIDs = Lists.newArrayList(userIDsSet);
    List<Long> uniqueItemIDs = Lists.newArrayList(itemIDsSet);

    Random random = RandomUtils.getRandom();
    final ClientRecommender client = getClient();

    ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(),
                                                            new NamedThreadFactory(true, "LoadTest"));
    Collection<Future<?>> futures = Lists.newArrayList();

    long start = System.currentTimeMillis();

    final RunningAverage recommendedBecause = new FullRunningAverage();
    final RunningAverage setPreference = new FullRunningAverage();
    final RunningAverage estimatePreference = new FullRunningAverage();
    final RunningAverage mostSimilarItems = new FullRunningAverage();
    final RunningAverage recommend = new FullRunningAverage();

    final AtomicInteger count = new AtomicInteger();

    for (int i = 0; i < ITERATIONS; i++) {
      final double r = random.nextDouble();
      final long userID = uniqueUserIDs.get(random.nextInt(uniqueUserIDs.size()));
      final long itemID = uniqueItemIDs.get(random.nextInt(uniqueItemIDs.size()));
      final float value = (float) random.nextInt(10);
      futures.add(executor.submit(new Callable<Void>() {
        @Override
        public Void call() throws TasteException {

          long stepStart = System.currentTimeMillis();
          if (r < 0.05) {
            client.recommendedBecause(userID, itemID, 10);
            recommendedBecause.addDatum(System.currentTimeMillis() - stepStart);
          } else if (r < 0.07) {
            client.setPreference(userID, itemID);
            setPreference.addDatum(System.currentTimeMillis() - stepStart);
          } else if (r < 0.1) {
            client.setPreference(userID, itemID, value);
            setPreference.addDatum(System.currentTimeMillis() - stepStart);
          } else if (r < 0.15) {
            client.estimatePreference(userID, itemID);
            estimatePreference.addDatum(System.currentTimeMillis() - stepStart);
          } else if (r < 0.2) {
            client.mostSimilarItems(new long[] {itemID}, 10);
            mostSimilarItems.addDatum(System.currentTimeMillis() - stepStart);
          } else {
            client.recommend(userID, 10);
            recommend.addDatum(System.currentTimeMillis() - stepStart);
          }

          int stepsFinished = count.incrementAndGet();
          if (stepsFinished % 1000 == 0) {
            log.info("Finished {} load steps", stepsFinished);
          }

          return null;
        }
      }));
    }

    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (ExecutionException ee) {
        log.warn("Error in execution", ee.getCause());
      }
    }

    executor.shutdown();

    long end = System.currentTimeMillis();
    log.info("Finished {} steps in {}ms", ITERATIONS, end - start);

    assertTrue(end - start < 400000L);

    log.info("recommendedBecause: {}", recommendedBecause);
    log.info("setPreference: {}", setPreference);
    log.info("estimatePreference: {}", estimatePreference);
    log.info("mostSimilarItems: {}", mostSimilarItems);
    log.info("recommend: {}", recommend);
  }

}
