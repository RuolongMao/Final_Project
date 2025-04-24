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

    def pivotClustering(graph: Graph[Int, Int], sc: SparkContext): Graph[Long, Int] = {
        val rand = new Random()

        // random permutation
        val piValues = graph.vertices.mapValues(_ => rand.nextDouble())

        var g = graph.outerJoinVertices(piValues) {
            case (_, _, Some(pi)) => pi
            case (_, _, None)     => Double.MaxValue
        }

        var currentVertices = g.vertices
        var currentEdges = g.edges
        var clustered = sc.emptyRDD[(VertexId, Long)]
        var clusterId: Long = 100L

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
            val newEdges = currentEdges.filter { e =>
                !bcClustered.value.contains(e.srcId) && !bcClustered.value.contains(e.dstId)
            }
            currentEdges = Graph(currentVertices, newEdges).edges


            clustered = clustered.union(newCluster)
            clusterId += 100 
        }
        val clusteredGraph = Graph(clustered.reduceByKey((a, _) => a), graph.edges)
        clusteredGraph
    }

    def main(args: Array[String]): Unit = {
        val conf = new SparkConf().setAppName("clustering")
        val sc = new SparkContext(conf)
        val spark = SparkSession.builder.config(conf).getOrCreate()

        if(args.length != 2) {
            println("Usage: clustering input_path output_path")
            sys.exit(1)
        }

        val inputPath = args(0)
        val outputPath = args(1)

        val edges = sc.textFile(inputPath)
        .filter(line => line.trim.nonEmpty && line.contains(","))
        .map { line =>
            val x = line.split(",")
            Edge(x(0).toLong, x(1).toLong, 1)
        }
        val g = Graph.fromEdges(edges, 1)
        val clustering = pivotClustering(g, sc)
        // output
        val output = spark.createDataFrame(clustering.vertices)
        output.coalesce(1).write.format("csv").mode("overwrite").save(args(1))
    }
}
