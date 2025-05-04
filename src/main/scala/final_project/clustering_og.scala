package final_project

import org.apache.spark.sql.SparkSession
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.graphx._
import scala.util.Random
import org.apache.log4j.{Level, Logger}

object clustering_og {
    val rootLogger = Logger.getRootLogger()
    rootLogger.setLevel(Level.ERROR)

    Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
    Logger.getLogger("org.spark-project").setLevel(Level.WARN)

    def pivotClustering(graph: Graph[Int, Int], sc: SparkContext): Graph[Long, Int] = {
        val rand = new Random(System.currentTimeMillis()) // better randomization

        // vertex IDs -- random values
        val piValues = graph.vertices.map { case (vid, _) =>
            val vertexRand = new Random(rand.nextLong() ^ vid)
            (vid, vertexRand.nextDouble())
        }
        
        // assign random values to og graph
        var g = graph.outerJoinVertices(piValues) {
            case (_, _, Some(pi)) => pi
            case (_, _, None)     => Double.MaxValue
        }

        var currentVertices = g.vertices
        var currentEdges = g.edges
        var clustered = sc.emptyRDD[(VertexId, Long)] //store clustered
        var clusterId: Long = 1L
        
        println(s"Initial vertex count: ${currentVertices.count()}")
        
        // prevent infinite loops
        var iteration = 0
        
        while (!currentVertices.isEmpty()) {
            iteration += 1
            val remainingCount = currentVertices.count()
            // if (remainingCount == 0) break

            println("----------------------------------------------------")
            println(s"Iteration $iteration, Remaining vertices: $remainingCount")
            println("----------------------------------------------------")

            val currentGraph = Graph(currentVertices, currentEdges)

            // val neighborIds = currentGraph.collectNeighborIds(EdgeDirection.Either)
            val neighborIds = currentEdges.flatMap { e =>
                if (e.srcId != e.dstId) Seq((e.srcId, e.dstId), (e.dstId, e.srcId)) else Seq.empty
            }.groupByKey()
            // print some 
            // neighborIds.take(2).foreach { case (vid, neighbors) =>
            //     println(s"vertex $vid has neighbors: ${neighbors.mkString(", ")}")
            // }

            // (vertexId, (Array[VertexId], Double))
            val vertexWithNeighborIds = currentVertices.leftOuterJoin(neighborIds).mapValues {
            case (value, maybeNeighbors) => (value, maybeNeighbors.getOrElse(Iterable.empty))
            }
            // only got neighbor id not values, so broadcast pivalues here
            val bcPiValues = sc.broadcast(piValues.collectAsMap())

            val localMinCandidates = vertexWithNeighborIds.filter { case (vid, (value, neighborIds)) =>
                val piVal = bcPiValues.value.getOrElse(vid, Double.MaxValue)
                // println(s"pival $piVal")

                if (neighborIds.isEmpty) { // No neighbors automatically local min
                    true
                } else {
                    neighborIds.forall { nid =>
                        val neighborPiVal = bcPiValues.value.getOrElse(nid, Double.MaxValue)
                        // println(s"npival $neighborPiVal")
                        piVal < neighborPiVal 
                    }
                }
            }

            val minCandidates = localMinCandidates.collect()
            println("----------------------------------------------------")
            println(s"Number of local minimum candidates: ${minCandidates.length}")
            println("----------------------------------------------------")

            // map pivot and neighbors to cluster id
            // val pivotAndNeighborAssignments = minCandidates.flatMap { case (pivotId, (_, neighborIds)) =>
            //     val thisPivotClusterId = clusterId 
            //     clusterId += 1 
            //     (pivotId +: neighborIds.toSeq).map(vid => (vid, thisPivotClusterId))
            // }
            val edgeSet = currentEdges.map(e => (e.srcId, e.dstId)).collect().toSet ++
              currentEdges.map(e => (e.dstId, e.srcId)).collect().toSet
            val bcEdgeSet = sc.broadcast(edgeSet)

            val pivotAndNeighborAssignments = minCandidates.flatMap { case (pivotId, (_, neighbors)) =>
                val neighborList = neighbors.toSeq.distinct
                val thisPivotClusterId = clusterId
                clusterId += 1

                val filteredNeighbors = neighborList.filter { nid =>
                    val numConnections = neighborList.count(other =>
                        other != nid && bcEdgeSet.value.contains((nid, other))
                    )
                    numConnections >= neighborList.size / 2
                }

                val clusterMembers = pivotId +: filteredNeighbors
                clusterMembers.map(vid => (vid, thisPivotClusterId))
            }

            // keep lower clusterId if multiple pivots try to own same vertex
            val pivotAndNeighborsRDD = sc.parallelize(pivotAndNeighborAssignments).reduceByKey(math.min) 

            println("----------------------------------------------------")
            val clusterSize = pivotAndNeighborsRDD.count()
            println(s"Assigned $clusterSize vertices to new clusters")
            println("----------------------------------------------------")

            clustered = clustered.union(pivotAndNeighborsRDD)

            // remove clustered vertices
            val clusteredIds = pivotAndNeighborsRDD.map(_._1).collect().toSet
            val bcClustered = sc.broadcast(clusteredIds)

            currentVertices = currentVertices.filter { case (vid, _) => !bcClustered.value.contains(vid) }

            // update new graph according to current vertices
            val newGraph = Graph(currentVertices, currentEdges).subgraph(
                vpred = (id, _) => !bcClustered.value.contains(id),
                epred = e => !bcClustered.value.contains(e.srcId) && !bcClustered.value.contains(e.dstId)
            )

            currentVertices = newGraph.vertices
            currentEdges = newGraph.edges

            if (iteration > 100) {
                println("WARNING: Reached too many iterations, breaking loop")
                break
            }
        }

        println("**----------------------------------------------------**")
        println(s"Clustering complete. Total clusters: ${clusterId - 1}")
        println("**----------------------------------------------------**")
        
        // Create final graph with cluster assignments
        val clusteredGraph = Graph(clustered, graph.edges)
        clusteredGraph
    }

    // Break helper
    def break = throw new BreakException()
    class BreakException extends RuntimeException

    def main(args: Array[String]): Unit = {
        val conf = new SparkConf().setAppName("clustering")
        val spark = SparkSession.builder.config(conf).getOrCreate()
        val sc = spark.sparkContext

        val startTime = System.currentTimeMillis()

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
        val endTime = System.currentTimeMillis()
        val executionTime = endTime - startTime

        // Print runtime in different formats
        println(s"Execution time: ${executionTime} ms")
        println(s"Execution time: ${executionTime / 1000.0} seconds")

        val output = spark.createDataFrame(clustering.vertices)
        output.coalesce(1).write.format("csv").mode("overwrite").save(args(1))
    }
}
