package clustering

import org.apache.spark.sql.SparkSession
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.graphx._
import scala.util.Random
import org.apache.log4j.{Level, Logger}

object main {
    val rootLogger = Logger.getRootLogger()
    rootLogger.setLevel(Level.ERROR)

    Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
    Logger.getLogger("org.spark-project").setLevel(Level.WARN)

    def pivotClustering(graph: Graph[Int, Int]): VertexRDD[Long] = {
        val rand = new Random()

        // random permutation
        val piValues = graph.vertices.mapValues(_ => rand.nextDouble())

        while (currentVertices.count() > 0) {
            // select pivot with smallest pi
            val minNeighborPi = Graph(currentVertices, currentEdges)
                .aggregateMessages[Double](
                triplet => {
                    if (triplet.srcAttr > triplet.dstAttr)
                    triplet.sendToSrc(triplet.dstAttr)
                    else if (triplet.dstAttr > triplet.srcAttr)
                    triplet.sendToDst(triplet.srcAttr)
                },
                (a, b) => math.min(a, b)
                )
        }
    }

    def main(args: Array[String]): Unit = {
        val conf = new SparkConf().setAppName("clustering")
        val sc = new SparkContext(conf)

        if(args.length != 2) {
            println("Usage: clustering input_path output_path")
            sys.exit(1)
        }

        val inputPath = args(0)
        val outputPath = args(1)

        val edges = sc.textFile(inputPath).filter(line => line.trim.nonEmpty && line.contains(",")).map(line => {val x = line.split(",")Edge(x(0).toLong, x(1).toLong, 1)})  
        val g = Graph.fromEdges(edges, 1)
        val clustering = pivotClustering(g)
        // output
        val result = clustering.map({ case (vid, cid) => s"$vid,$cid" })
        result.saveAsTextFile(outputPath)
    }
}
