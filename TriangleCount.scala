import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql._
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions.Window
import scala.collection.mutable
import org.apache.spark.sql.functions._


object TriangleCount {
  def main(args: Array[String]): Unit = {
    Logger.getLogger("org").setLevel(Level.ERROR)
    val spark = SparkSession.builder()
      .master("local[7]")
      .appName("TriangleCount")
      .config("spark.executor.instances", "*")
      .getOrCreate()

    spark.time {

      val start: Double = System.nanoTime()
      import spark.implicits._
      val path = "/home/ozzy/Downloads/as-caida"

      //Load files that contain the graphs
      val df = spark.read
        .option("header", false)
        .option("inferSchema", true)
        .option("delimiter", "\n")
        .option("comment", "#")
        .csv(path)
        .withColumn("file_name", input_file_name())

      import spark.implicits._

      // ========================= Preprocessing =============================================== //
      //First we split each edge into two columns: src and dest
      val processed_df = df.withColumn("source", split(col("_c0"), "\\s+"))
        .withColumn("src", element_at(col("source"), 1).cast("int"))
        .withColumn("dest", element_at(col("source"), 2).cast("int"))
        .drop("_c0", "source")

      val win = Window.orderBy("file_name")

      //At each row, we combine the src and dest into the desired form "(src,dest)" and then we group by file name
      //We also add the index of the file and remove the file-name
      val processed_df2 = processed_df.select(col("file_name"), concat(lit('('), col("src"), lit(','),
        col("dest"), lit(')')).as("pair"))
        .groupBy("file_name")
        .agg(concat_ws(" ", collect_list("pair")) as "edges")
        .withColumn("index", row_number().over(win))
        .drop("file_name")

      //We reverse the columns, so that index comes first
      val cols = processed_df2.columns.map(processed_df2(_)).reverse
      val final_df3 = processed_df2.select(cols: _*)
      //final_df3.show(4, false)
      //final_df3.printSchema()

      //Store to a file
      //final_df3.write.csv("/home/ozzy/Downloads/data/test3")

      /// ======================== Triangle Count ================================================= //
      val final_df = df.withColumn("source", split(col("_c0"), "\\s+"))
        .withColumn("src", element_at(col("source"), 1).cast("int"))
        .withColumn("dest", element_at(col("source"), 2).cast("int"))
        .withColumn("pairs", array("src", "dest"))
        .drop("_c0", "source")
        .groupBy("file_name", "src")
        .agg(collect_list("dest") as "neighbors")
        .orderBy("src")
        .withColumn("temp", struct("src", "neighbors"))
        .drop("src", "neighbors")
        .groupBy("file_name")
        .agg(collect_list("temp") as "adj_list")
      //final_df.printSchema()
      //final_df.show(10,false)

      //Create a UDF that transforms a struct to map
      val structToMap = (value: Seq[Row]) => value.map(v => v.getInt(0) -> v.getSeq(1).asInstanceOf[Seq[Int]]).toMap
      val structToMapUDF = udf(structToMap)
      val final_df2 = final_df.select($"file_name", structToMapUDF($"adj_list")) //.show(false)
      val win2 = Window.orderBy("file_name")
      val temp = final_df2.withColumn("index", row_number().over(win2)).drop("file_name")
      val cols2 = temp.columns.map(temp(_)).reverse
      val temp2 = temp.select(cols2: _*)
      //temp2.show(4,false)

      def countTriangle(lines: Map[Integer, Array[Integer]]): Array[String] = {
        //Initialize a Map that stores the set of triangles found in current graph
        val trianglesMap = mutable.HashMap[String, Set[String]]()
        //for each source find its neighbors
        lines.keys.foreach(i => {
          val neighbors = lines.getOrElse(i, new Array[Integer](0))
          neighbors.foreach(j => {
            //for each pair of neighbors, check if they are also neighbors
            val v = lines.getOrElse(j, new Array[Integer](0))
            neighbors.foreach(s => {
              if (i != j && j != s && i != s) {
                //nodes v and s are neighbors if s in in v's adjacency list
                if (v.contains(s)) {
                  val list = List(i, j, s).sorted.toString()
                  if (trianglesMap.contains("triangles"))
                    trianglesMap.update("triangles", trianglesMap("triangles") + list.mkString(""))
                  else trianglesMap.update("triangles", Set(list.mkString("")))
                }
              }
            })
          })
        })
        //print(1)
        trianglesMap("triangles").toArray
      }

      val countTriangles = udf(countTriangle _)
      val trianglesDf = final_df2.withColumn("triangles_array", countTriangles(col("UDF(adj_list)")))
      //Explode the column with triangles and count their frequency
      val frequenciesDF = trianglesDf.withColumn("node-triangles", explode($"triangles_array")).select("node-triangles")

      val rdd = frequenciesDF.rdd.map(x => (x, 1)).reduceByKey(_ + _).sortBy(_._2, false)
      rdd.map { case (a, b) =>
        var line = a.toString + "," + b.toString
        line
      }.coalesce(1).saveAsTextFile("/home/ozzy/Downloads/caida")
      
      val end: Double = System.nanoTime()
      println(end-start)
    }
  }
}