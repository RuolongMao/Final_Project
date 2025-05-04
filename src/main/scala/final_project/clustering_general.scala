package final_project

import org.apache.spark.sql.SparkSession
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.graphx._
import scala.util.Random
import org.apache.log4j.{Level, Logger}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.rdd.RDD

object clustering_general {
    // Configure logging
    val rootLogger = Logger.getRootLogger()
    rootLogger.setLevel(Level.ERROR)
    Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
    Logger.getLogger("org.spark-project").setLevel(Level.WARN)

    def pivotClustering(graph: Graph[Int, Int], sc: SparkContext): Graph[Long, Int] = {
        // Initialize random number generator and assign random values to vertices
        val rand = new Random(System.currentTimeMillis())
        val piValues = graph.vertices.mapValues(_ => rand.nextDouble())
        
        // Create initial graph with random values
        var g = graph.outerJoinVertices(piValues) {
            case (_, _, Some(pi)) => pi
            case (_, _, None)     => Double.MaxValue
        }

        // Initialize variables for clustering process
        var currentVertices = g.vertices
        var currentEdges = g.edges
        var clustered = sc.emptyRDD[(VertexId, Long)]
        var clusterId: Long = 1L
        
        println(s"Initial vertex count: ${currentVertices.count()}")
        
        // Main clustering loop
        var iteration = 0
        val piValuesMap = piValues.persist(StorageLevel.MEMORY_AND_DISK)
        
        while (!currentVertices.isEmpty()) {
            iteration += 1
            val remainingCount = currentVertices.count()

            println("----------------------------------------------------")
            println(s"Iteration $iteration, Remaining vertices: $remainingCount")
            println("----------------------------------------------------")

            // Get neighbor information for each vertex
            val neighborIds = currentEdges.flatMap { e =>
                if (e.srcId != e.dstId) Seq((e.srcId, e.dstId), (e.dstId, e.srcId)) else Seq.empty
            }.groupByKey()

            // Join vertices with their pi values and neighbors
            val vertexWithNeighborPis = currentVertices.join(piValuesMap).leftOuterJoin(neighborIds).mapValues {
                case ((value, piVal), maybeNeighbors) => 
                    (piVal, maybeNeighbors.getOrElse(Iterable.empty))
            }

            // Create broadcast variable for neighbor pi values
            val neighborPiMap = piValuesMap.collectAsMap()
            val bcNeighborPiMap = sc.broadcast(neighborPiMap)

            // Find local minimum candidates
            val localMinCandidates = vertexWithNeighborPis.filter { case (vid, (piVal, neighbors)) =>
                if (neighbors.isEmpty) {
                    true
                } else {
                    neighbors.forall { nid =>
                        val neighborPiVal = bcNeighborPiMap.value.getOrElse(nid, Double.MaxValue)
                        piVal <= neighborPiVal
                    }
                }
            }

            // Assign cluster IDs to pivots and their neighbors
            val pivotAndNeighborAssignments = localMinCandidates.flatMap { case (pivotId, (_, neighbors)) =>
                val thisPivotClusterId = clusterId
                clusterId += 1
                (pivotId +: neighbors.toSeq).map(vid => (vid, thisPivotClusterId))
            }

            // Resolve conflicts by keeping lower cluster ID
            val pivotAndNeighborsRDD = pivotAndNeighborAssignments.reduceByKey((a, b) => math.min(a, b))
            val uniqueClustersThisIteration = pivotAndNeighborsRDD.map(_._2).distinct().count()

            // Update clustered vertices
            clustered = clustered.union(pivotAndNeighborsRDD)

            // Remove clustered vertices from current graph
            val clusteredIdsRDD = pivotAndNeighborsRDD.map(_._1)
            val clusteredIdsSet = clusteredIdsRDD.distinct().map((_, null))
            
            currentVertices = VertexRDD(currentVertices.subtractByKey(clusteredIdsSet))
            
            // Update edges to remove those connected to clustered vertices
            val currentVerticesSet = currentVertices.map(_._1).collect().toSet
            val bcCurrentVertices = sc.broadcast(currentVerticesSet)
            
            currentEdges = EdgeRDD.fromEdges(currentEdges.filter { e =>
                bcCurrentVertices.value.contains(e.srcId) && bcCurrentVertices.value.contains(e.dstId)
            })

            clusterId += uniqueClustersThisIteration

            // Safety check to prevent infinite loops
            if (iteration > 100) {
                println("WARNING: Reached too many iterations, breaking loop")
                break
            }
        }

        println("**----------------------------------------------------**")
        println(s"Clustering complete. Total clusters: ${clusterId - 1}")
        println("**----------------------------------------------------**")
        
        // Create final graph with cluster assignments
        Graph(clustered, graph.edges)
    }

    // Break helper
    def break = throw new BreakException()
    class BreakException extends RuntimeException

    def main(args: Array[String]): Unit = {
        val conf = new SparkConf().setAppName("clustering")
        val spark = SparkSession.builder.config(conf).getOrCreate()
        val sc = spark.sparkContext

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