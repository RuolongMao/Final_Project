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

    def pivotClustering(graph: Graph[Int, Int], sc: SparkContext): VertexRDD[Long] = {
        val rand = new Random()

        var currentVertices = graph.vertices
        var currentEdges = graph.edges
        // random permutation
        val piValues = currentVertices.mapValues(_ => rand.nextDouble())
        var clustered = sc.emptyRDD[(VertexId, Long)]
        var clusterId = 100

        currentVertices.persist()

        while (!currentVertices.isEmpty()) {
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
            
            val pivots = currentVertices.leftOuterJoin(minNeighborPi).filter { case (_, (pi, min)) => min.isEmpty || pi < min.get }.mapValues(_ => clusterId)


            // Every node joins the pivots with the smallest pi
            val pivotGraph = Graph(pivots, currentEdges)

            val assignments = pivotGraph.aggregateMessages[(VertexId, Double)](
                triplet => {
                triplet.sendToDst((triplet.srcId, triplet.srcAttr))
                triplet.sendToSrc((triplet.dstId, triplet.dstAttr))
                },
                (a, b) => if (a._2 < b._2) a else b
            ).mapValues(_ => clusterId)

            val newCluster = pivots.union(assignments)

            // Remove clustered nodes
            val clusteredIds = newCluster.map(_._1).collect().toSet
            val bcClustered = sc.broadcast(clusteredIds)

            currentVertices = currentVertices.filter { case (vid, _) => !bcClustered.value.contains(vid) }
            currentEdges = currentEdges.filter { e =>
                !bcClustered.value.contains(e.srcId) && !bcClustered.value.contains(e.dstId)
            }

            clustered = clustered.union(newCluster)
            clusterId += 100 
        }
        VertexRDD(clustered)
    }

    def main(args: Array[String]): Unit = {
        val conf = new SparkConf().setAppName("clustering")
        val sc = new SparkContext(conf)

        if(args.length != 2) {
            println("Usage: clustering input_path output_path")
            sys.exit(1)
        }

        val inputPath = args(1)
        val outputPath = args(2)

        val edges = sc.textFile(inputPath).filter(line => line.trim.nonEmpty && line.contains(",")).map(line => {val x = line.split(",")Edge(x(0).toLong, x(1).toLong, 1)})  
        val g = Graph.fromEdges(edges, 1)
        val clustering = pivotClustering(g)
        // output
        val result = clustering.map({ case (vid, cid) => s"$vid,$cid" })
        result.saveAsTextFile(outputPath)
    }
}
