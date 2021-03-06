/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.jobs.mapreduce

import java.io.{Closeable, InputStream}

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.fs.{Path, Seekable}
import org.apache.hadoop.mapreduce._
import org.locationtech.geomesa.convert.SimpleFeatureConverters
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

/**
 * Input format for Converters gives us access to the entire file as a byte stream
 * via the record reader.
 */
class ConverterInputFormat extends FileStreamInputFormat {
  override def createRecordReader(): FileStreamRecordReader = new ConverterRecordReader
}

object ConverterInputFormat {

  object Counters {
    val Group     = "org.locationtech.geomesa.jobs.convert"
    val Converted = "converted"
    val Failed    = "failed"
  }

  val ConverterKey  = "org.locationtech.geomesa.jobs.ingest.converter"

  def setConverterConfig(job: Job, config: String): Unit = job.getConfiguration.set(ConverterKey, config)

  def setSft(job: Job, sft: SimpleFeatureType): Unit = FileStreamInputFormat.setSft(job, sft)
}

class ConverterRecordReader extends FileStreamRecordReader with LazyLogging {

  import ConverterInputFormat._

  override def createIterator(stream: InputStream with Seekable,
                              filePath: Path,
                              context: TaskAttemptContext): Iterator[SimpleFeature] with Closeable = {
    val confStr   = context.getConfiguration.get(ConverterKey)
    val conf      = ConfigFactory.parseString(confStr)
    val sft       = FileStreamInputFormat.getSft(context.getConfiguration)
    val converter = SimpleFeatureConverters.build(sft, conf)

    class MapReduceCounter extends org.locationtech.geomesa.convert.Transformers.Counter {
      import ConverterInputFormat.{Counters => C}

      // Global counters for the entire job
      override def incSuccess(i: Long): Unit   = context.getCounter(C.Group, C.Converted).increment(i)
      override def getSuccess: Long            = context.getCounter(C.Group, C.Converted).getValue

      override def incFailure(i: Long): Unit   = context.getCounter(C.Group, C.Failed).increment(i)
      override def getFailure: Long            = context.getCounter(C.Group, C.Failed).getValue

      // Line counts are local to file not global
      private var c: Long = 0
      override def incLineCount(i: Long = 1): Unit = c += i
      override def getLineCount: Long              = c
      override def setLineCount(i: Long): Unit     = c = i
    }

    val ec = converter.createEvaluationContext(Map("inputFilePath" -> filePath.toString), new MapReduceCounter)
    val iter = converter.process(stream, ec)

    logger.info(s"Initialized record reader on split ${filePath.toString} with " +
        s"type name ${sft.getTypeName} and convert conf $confStr")

    new Iterator[SimpleFeature] with Closeable {
      override def hasNext: Boolean = iter.hasNext
      override def next(): SimpleFeature = iter.next
      override def close(): Unit = converter.close()
    }
  }
}
