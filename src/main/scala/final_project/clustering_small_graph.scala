package final_project

import org.apache.spark.sql.SparkSession
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.graphx._
import scala.util.Random
import org.apache.spark.storage.StorageLevel
import org.apache.spark.rdd.RDD

object clustering_small_graph {

    def optimizeClustering(graph: Graph[Long, Int], sc: SparkContext): Graph[Long, Int] = {
        var improved = true
        var iteration = 0
        val maxIterations = 10
        var currentGraph = graph

        while (improved && iteration < maxIterations) {
            val startTime = System.currentTimeMillis()
            iteration += 1

            val vertexClusters = currentGraph.vertices.collectAsMap()
            val bcVertexClusters = sc.broadcast(vertexClusters)
            
            val clusterSizes = currentGraph.vertices.map(v => (v._2, 1L)).reduceByKey(_ + _).collectAsMap()
            val bcClusterSizes = sc.broadcast(clusterSizes)

            val degrees = currentGraph.degrees
            val graphWithDegrees = currentGraph.outerJoinVertices(degrees) {
                case (id, cluster, degOpt) => (cluster, degOpt.getOrElse(0))
            }

            val sameClusterEdges = graphWithDegrees.aggregateMessages[Int](
                e => if (e.srcAttr._1 == e.dstAttr._1) {
                    e.sendToDst(1)
                    e.sendToSrc(1)
                },
                _ + _
            )

            val graphWithAgreement = graphWithDegrees.outerJoinVertices(sameClusterEdges) {
                case (id, (cluster, degree), agreementOpt) => (cluster, degree, agreementOpt.getOrElse(0))
            }

            val vertexNeighbors = currentGraph.collectNeighborIds(EdgeDirection.Either)
            
            val potentialImprovements = vertexNeighbors.map { case (vid, neighbors) =>
                val currentCluster = vertexClusters(vid)
                val currentClusterSize = clusterSizes.getOrElse(currentCluster, 0L)
                
                val currentSameClusterNeighbors = neighbors.count(nid => bcVertexClusters.value.getOrElse(nid, -1L) == currentCluster)
                val currentDisagreement = (currentClusterSize - 1 - currentSameClusterNeighbors) + (neighbors.length - currentSameClusterNeighbors)
                
                val neighborClusters = neighbors.map(nid => bcVertexClusters.value.getOrElse(nid, -1L)).filter(_ != currentCluster).distinct
                
                // Calculate disagreement if vertex moved to each neighbor cluster
                val clusterImprovements = neighborClusters.map { cluster =>
                    val newClusterSize = bcClusterSizes.value.getOrElse(cluster, 0L)
                    val sameClusterNeighbors = neighbors.count(nid => bcVertexClusters.value.getOrElse(nid, -1L) == cluster)
                    
                    val newDisagreement = ((newClusterSize + 1) - 1 - sameClusterNeighbors) + (neighbors.length - sameClusterNeighbors)
                    
                    val oldClusterRemovalDisagreement = (currentClusterSize - 1) - 1 - currentSameClusterNeighbors
                    
                    (cluster, newDisagreement - currentDisagreement)
                }
                
                if (clusterImprovements.nonEmpty) {
                    val bestImprovement = clusterImprovements.minBy(_._2)
                    if (bestImprovement._2 < 0) Some((vid, bestImprovement._1)) else None
                } else {
                    None
                }
            }.filter(_.isDefined).map(_.get)

            val improvements = potentialImprovements.collect()
            if (improvements.isEmpty) {
                improved = false
            } else {
                val improvementsMap = improvements.toMap
                val bcImprovements = sc.broadcast(improvementsMap)
                
                currentGraph = Graph(
                    currentGraph.vertices.map { case (vid, cluster) =>
                        (vid, bcImprovements.value.getOrElse(vid, cluster))
                    },
                    currentGraph.edges
                )
            }
            
            val endTime = System.currentTimeMillis()
        }

        val finalVertexClusters = currentGraph.vertices.collectAsMap()
        val bcFinalVertexClusters = sc.broadcast(finalVertexClusters)
        
        val crossClusterEdges = if (currentGraph.edges != null) {
            currentGraph.edges.filter { edge =>
                val srcCluster = bcFinalVertexClusters.value.getOrElse(edge.srcId, -1L)
                val dstCluster = bcFinalVertexClusters.value.getOrElse(edge.dstId, -1L)
                srcCluster != dstCluster
            }.count()
        } else {
            currentGraph.triplets.filter(triplet => 
                triplet.srcAttr != triplet.dstAttr
            ).count()
        }
        
        val finalClusterSizes = currentGraph.vertices.map(v => (v._2, 1L)).reduceByKey(_ + _).collectAsMap()
        val bcFinalClusterSizes = sc.broadcast(finalClusterSizes)
        
        val vertexNeighborIds = currentGraph.collectNeighborIds(EdgeDirection.Either)
        
        val missingEdges = vertexNeighborIds.join(currentGraph.vertices).map { 
            case (vid, (neighbors, cluster)) =>
                val clusterSize = bcFinalClusterSizes.value.getOrElse(cluster, 0L)
                val sameClusterNeighbors = neighbors.count(nid => 
                    bcFinalVertexClusters.value.getOrElse(nid, -1L) == cluster)
                (clusterSize - 1 - sameClusterNeighbors)
        }.sum() / 2 // since each missing edge is counted twice
        
        val totalDisagreement = crossClusterEdges + missingEdges
        
        currentGraph
    }

    def pivotClustering(graph: Graph[Int, Int], sc: SparkContext): Graph[Long, Int] = {
        // 1746364080000L
        val rand = new Random(System.currentTimeMillis())

        val piValues = graph.vertices.map { case (vid, _) =>
            val vertexRand = new Random(rand.nextLong() ^ vid)
            (vid, vertexRand.nextDouble())
        }
        
        var g = graph.outerJoinVertices(piValues) {
            case (_, _, Some(pi)) => pi
            case (_, _, None)     => Double.MaxValue
        }

        var currentVertices = g.vertices
        var currentEdges = g.edges
        var clustered = sc.emptyRDD[(VertexId, Long)]
        var clusterId: Long = 1L
                
        var iteration = 0
        val piValuesMap = piValues.persist(StorageLevel.MEMORY_AND_DISK)
        
        while (!currentVertices.isEmpty()) {
            iteration += 1
            val remainingCount = currentVertices.count()

            val currentGraph = Graph(currentVertices, currentEdges)

            val neighborIds = currentEdges.flatMap { e =>
                if (e.srcId != e.dstId) Seq((e.srcId, e.dstId), (e.dstId, e.srcId)) else Seq.empty
            }.groupByKey()

            val vertexWithNeighborPis = currentVertices.join(piValuesMap).leftOuterJoin(neighborIds).mapValues {
                case ((value, piVal), maybeNeighbors) => 
                    (piVal, maybeNeighbors.getOrElse(Iterable.empty))
            }

            val neighborPiMap = piValuesMap.collectAsMap()
            val bcNeighborPiMap = sc.broadcast(neighborPiMap)

            val localMinCandidates = vertexWithNeighborPis.filter { case (vid, (piVal, neighbors)) =>
                if (neighbors.isEmpty) {
                    true
                } else {
                    val isLocalMin = neighbors.forall { nid =>
                        val neighborPiVal = bcNeighborPiMap.value.getOrElse(nid, Double.MaxValue)
                        piVal < neighborPiVal
                    }
                    isLocalMin
                }
            }

            // Simple neightbor assignment
            // val pivotAndNeighborAssignments = localMinCandidates.flatMap { case (pivotId, (_, neighbors)) =>
            //     val thisPivotClusterId = clusterId
            //     clusterId += 1
            //     (pivotId +: neighbors.toSeq).map(vid => (vid, thisPivotClusterId))
            // }

            // Neighbor assignment considering inter-connectivity
            val edgeSet = currentEdges.map(e => (e.srcId, e.dstId)).collect().toSet ++
              currentEdges.map(e => (e.dstId, e.srcId)).collect().toSet
            val bcEdgeSet = sc.broadcast(edgeSet)

            val pivotAndNeighborAssignments = localMinCandidates.flatMap { case (pivotId, (_, initialNeighbors)) =>
                val neighborList = initialNeighbors.toSeq.distinct
                val thisPivotClusterId = clusterId
                clusterId += 1
                
                val connectivityThreshold = 0.5
                var currentNeighbors = neighborList
                var prevSize = -1
                var stableCluster = false
                
                while (!stableCluster) {
                    prevSize = currentNeighbors.size
                    
                    val neighborConnectivity = currentNeighbors.map { nid =>
                        val numConnections = currentNeighbors.count(other =>
                            other != nid && bcEdgeSet.value.contains((nid, other))
                        )
                        // Calculate connectivity ratio (excluding self from denominator)
                        val connectivityRatio = if (currentNeighbors.size > 1) {
                            numConnections.toDouble / (currentNeighbors.size - 1)
                        } else {
                            1.0 // If only neighbor
                        }
                        (nid, connectivityRatio)
                    }
                    
                    currentNeighbors = neighborConnectivity
                        .filter(_._2 >= connectivityThreshold)
                        .map(_._1)
                    
                    stableCluster = currentNeighbors.size == prevSize
                }
                
                val clusterMembers = pivotId +: currentNeighbors
                clusterMembers.map(vid => (vid, thisPivotClusterId))
            }

            val pivotAndNeighborsRDD = pivotAndNeighborAssignments.reduceByKey((a, b) => math.min(a, b))
            val uniqueClustersThisIteration = pivotAndNeighborsRDD.map(_._2).distinct().count()

            clustered = clustered.union(pivotAndNeighborsRDD)
            val clusteredIdsRDD = pivotAndNeighborsRDD.map(_._1)
            val clusteredIdsSet = clusteredIdsRDD.distinct().map((_, null))
            
            currentVertices = VertexRDD(currentVertices.subtractByKey(clusteredIdsSet))
            
            val currentVerticesSet = currentVertices.map(_._1).collect().toSet
            val bcCurrentVertices = sc.broadcast(currentVerticesSet)
            
            currentEdges = EdgeRDD.fromEdges(currentEdges.filter { e =>
                bcCurrentVertices.value.contains(e.srcId) && bcCurrentVertices.value.contains(e.dstId)
            })

            clusterId += uniqueClustersThisIteration

            if (iteration > 100) {
                break
            }
        }
        // Clustering without optimization attempt
        val initialClustering = Graph(clustered, graph.edges)
        // initialClustering

        // Uncomment below to optimize the clustering
        val optimizedClustering = optimizeClustering(initialClustering, sc)
        optimizedClustering
    }

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
