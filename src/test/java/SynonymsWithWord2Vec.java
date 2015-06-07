/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.mllib.feature.Word2Vec;
import org.apache.spark.mllib.feature.Word2VecModel;
import org.elasticsearch.spark.rdd.api.java.JavaEsSpark;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import scala.Tuple2;
import scala.collection.Iterator;

import java.io.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static scala.collection.JavaConversions.propertiesAsScalaMap;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SynonymsWithWord2Vec implements Serializable {

    private static final Properties ES_SPARK_CFG = new Properties();

    static {
        ES_SPARK_CFG.setProperty("es.nodes", "localhost");
        ES_SPARK_CFG.setProperty("es.port", "9200");
    }

    private static final transient SparkConf conf = new SparkConf().setAll(propertiesAsScalaMap(ES_SPARK_CFG)).setMaster(
            "local").setAppName("estest");
    private static transient JavaSparkContext sc = null;

    @BeforeClass
    public static void setup() {
        sc = new JavaSparkContext(conf);
    }

    @AfterClass
    public static void clean() throws Exception {
        if (sc != null) {
            sc.stop();
            // wait for jetty & spark to properly shutdown
            Thread.sleep(TimeUnit.SECONDS.toMillis(2));
        }
    }

    /**
     * Compute cooccurrence as desribed here:
     * 1. https://spark.apache.org/docs/1.2.0/api/java/org/apache/spark/mllib/feature/Word2Vec.html
     * <p/>
     * Could maybe also use:
     * http://spark.apache.org/docs/1.2.0/mllib-collaborative-filtering.html
     * or even:
     * https://databricks.com/blog/2014/10/20/efficient-similarity-algorithm-now-in-spark-twitter.html
     *
     * This needs token-plugin installed: https://github.com/brwe/es-token-plugin
     */
    @Test
    public void synonymsInMovieReviews() throws Exception {

        String target = "movie-reviews/review";
        JavaPairRDD<String, Map<String, Object>> esRDD = JavaEsSpark.esRDD(sc, target,
                "{\"fields\": [\"text.analyzed\"]}");

        // get the analyzed text from the results
        JavaRDD<Iterable<String>> corpus = esRDD.map(
                new Function<Tuple2<String, Map<String, Object>>, Iterable<String>>() {
                    public Iterable<String> call(Tuple2<String, Map<String, Object>> s) {
                        return (Iterable<String>) s._2().get("text.analyzed");
                    }
                }
        );

        // print some lines so we know how the data looks like
        System.out.println(esRDD.take(2));
        System.out.println(corpus.take(2));
        // learn the word vectors
        Word2Vec vectorModel = new Word2Vec();
        Word2VecModel model = vectorModel.fit(corpus);
        // find an example synonym and print it
        Tuple2<String, Object>[] synonyms = model.findSynonyms("action", 10);
        for (Tuple2<String, Object> synonym : synonyms) {
            System.out.println(synonym._1().toString());
            System.out.println(synonym._2().toString());
        }

        // now write synonyms for each word to a file in the format
        // word => word, synonym1, synonym2, ...
        Iterator<Tuple2<String, float[]>> words = model.getVectors().seq().iterator();
        FileOutputStream fos = new FileOutputStream("synonyms.txt");
        try (OutputStream out = new BufferedOutputStream(
                fos)) {
            while (words.hasNext()) {
                Tuple2<String, float[]> word = words.next();
                Tuple2<String, Object>[] similarWords = model.findSynonyms(word._1(), 10);
                String synonymLineStart =  word._1() + "=>" + word._1() + ",";
                out.write(synonymLineStart.getBytes());
                int numSimilarWords = 0;
                for (Tuple2<String, Object> similarWord : similarWords) {
                    out.write(similarWord._1().getBytes());
                    if (numSimilarWords < similarWords.length - 1) {
                        out.write(",".getBytes());
                    }
                    numSimilarWords++;
                }
                out.write("\n".getBytes());
            }
            out.flush();
            out.close();
        } catch (IOException x) {
            System.err.println(x);
        }
    }
}