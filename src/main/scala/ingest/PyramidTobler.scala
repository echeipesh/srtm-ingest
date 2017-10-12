package ingest

import geotrellis.proj4._
import geotrellis.raster._
import geotrellis.raster.io._
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.kryo._
import geotrellis.spark.io.s3._
import geotrellis.spark.io.file._
import geotrellis.spark.io.index._
import geotrellis.spark.tiling._
import geotrellis.spark.pyramid._
import geotrellis.vector._

import org.apache.spark._
import org.apache.spark.rdd._
import org.apache.spark.serializer.KryoSerializer

object ToblerPyramid {
  def main(args: Array[String]): Unit = {

    val catalog = "s3://geotrellis-test/dg-srtm"
    val bucket = "geotrellis-test"
    val prefix = "dg-srtm"

    val layerName = "srtm-wsg84-gps"

    val resultName = "tobler-tiles"
    val layoutScheme = ZoomedLayoutScheme(WebMercator)

    val numPartitions =
      if(args.length > 0) {
        args(0).toInt
      } else {
        5000
      }

    val conf = new SparkConf()
      .setIfMissing("spark.master", "local[*]")
      .setAppName("Ingest DEM")
      .set("spark.serializer", classOf[KryoSerializer].getName)
      .set("spark.kryo.registrator", classOf[KryoRegistrator].getName)
      .set("spark.driver-memory", "10000m")
      .set("spark.driver.cores", "4")
      .set("spark.executor.memory", "5120M")
      .set("spark.executor.cores", "2")
      .set("spark.yarn.executor.memoryOverhead","700M")
      .set( "spark.driver.maxResultSize", "3g")

    implicit val sc = new SparkContext(conf)

    try {
      val layerReader = S3LayerReader(bucket, prefix)
      val layerWriter = if (conf.get("spark.master") == "local[*]")
                          FileLayerWriter("/tmp/dg-srtm")
                        else
                          S3LayerWriter(bucket, prefix)

      val queryExtent = Extent(
        -120.36209106445312,38.8407772667165,
        -119.83612060546874,39.30242456041487)

      val srtm =
        layerReader.read[SpatialKey, MultibandTile, TileLayerMetadata[SpatialKey]](
          LayerId(layerName, 0),
          numPartitions=numPartitions
        )//.where(Intersects(queryExtent)).result

      val tobler =
        srtm
          .withContext(_.mapValues(_.band(0)))
          .slope()
          .withContext { rdd =>
            rdd.mapValues { tile =>
              tile.mapDouble { z =>
                val radians = z * math.Pi / 180.0
                val tobler_kph = 6 * (math.pow(2.718281828, (-3.5 * math.abs(radians + 0.05))))
                3.6 / tobler_kph
              }
            }
        }

      val options = null
      val (zoom, tiles) = tobler.reproject(layoutScheme.crs,
                                           layoutScheme.levelForZoom(14).layout,
                                           bufferSize=5,
                                           options=geotrellis.raster.resample.Bilinear)

      Pyramid.levelStream(tiles, layoutScheme, zoom, 0).foreach { case (z, layer) =>
        val lid = LayerId(resultName, z)
        layerWriter.write(lid, layer, ZCurveKeyIndexMethod)
      }
    } finally {
      sc.stop()
    }
  }
}
