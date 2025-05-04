package final_project

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.graphx._
import org.apache.spark.storage.StorageLevel

object clustering_verifier{
  def main(args: Array[String]) {

    val conf = new SparkConf().setAppName("verifier")
    val sc = new SparkContext(conf)
    val spark = SparkSession.builder.config(conf).getOrCreate()

    sc.setLogLevel("ERROR")

    if(args.length != 2) {
      println("Usage: verifier graph_path matching_path")
      sys.exit(1)
    }

    def line_to_canonical_edge(line: String): Edge[Int] = {
      val x = line.split(",");
      if(x(0).toLong < x(1).toLong)
        Edge(x(0).toLong, x(1).toLong, 1)
      else
        Edge(x(1).toLong, x(0).toLong, 1)
    }

    case class VertexData(data1: Long, data2: Long)
      
    val graph_edges = sc.textFile(args(0)).map(line_to_canonical_edge)
    val graph_vertices = sc.textFile(args(1)).map(line => line.split(",")).map( parts => (parts(0).toLong, parts(1).toLong) )

    if(graph_vertices.keys.distinct.count != graph_vertices.count){
      println("A vertex ID showed up more than once in the solution file.")
      sys.exit(1)      
    } 

    val original_vertices = Graph.fromEdges[Int, Int](graph_edges, 0).vertices.keys
    if(!(original_vertices.subtract(graph_vertices.keys).isEmpty() && graph_vertices.keys.subtract(original_vertices).isEmpty() )){
      println("The set of vertices in the solution file does not match that of the inpout file.")
      sys.exit(1)      
    }

    // Create a graph with cluster assignments
    val graph = Graph(graph_vertices, graph_edges)
    
    // Create a broadcast variable for vertex cluster assignments
    val vertexClusters = graph.vertices.collectAsMap()
    val bcVertexClusters = sc.broadcast(vertexClusters)

    // Calculate disagreement by counting edges between different clusters
    val disagreement = graph.edges.filter { edge =>
      val srcCluster = bcVertexClusters.value.getOrElse(edge.srcId, -1L)
      val dstCluster = bcVertexClusters.value.getOrElse(edge.dstId, -1L)
      srcCluster != dstCluster
    }.count()

    println("The clustering has a disagreement of: " + disagreement)
  }
}
