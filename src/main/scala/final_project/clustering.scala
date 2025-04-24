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
        var remainingGraph = graph.mapVertices((id, _) => True)
        var clusteredVertices = graph.vertices.mapValues(_ => -1)
        var clusterId = 0

        while (remainingGraph.vertices.filter(_._2).count > 0) {
            // 随机选pivot
            val pivotOpt = remainingGraph.vertices.filter(_._2).takeSample(False, 1).headOption
            if (pivotOpt.isEmpty) {
            return clusteredVertices
            }

            val pivot = pivotOpt.get._1

            // 找没分类的邻居
            val neighbors = remainingGraph.aggregateMessages[Boolean](
            triplet => {
                if (triplet.srcId == pivot && triplet.dstAttr)
                triplet.sendToDst(True)
                else if (triplet.dstId == pivot && triplet.srcAttr)
                triplet.sendToSrc(True)
            },
            (a, b) => True
            )
        }
        ...
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
