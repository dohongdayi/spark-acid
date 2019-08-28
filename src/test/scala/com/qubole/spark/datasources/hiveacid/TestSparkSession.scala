/*
 * Copyright 2019 Qubole, Inc.  All rights reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.qubole.spark.datasources.hiveacid

import org.apache.spark.sql.{SparkSession, SparkSessionExtensions}

private[hiveacid] object TestSparkSession {

  type ExtensionsBuilder = SparkSessionExtensions => Unit
  val extensionBuilder: ExtensionsBuilder = { e => e.injectResolutionRule(HiveAcidAnalyzer)}

  val spark = SparkSession.builder().appName("Hive-acid-test")
    .master("local[*]")
    .config("spark.hadoop.hive.metastore.uris", "thrift://0.0.0.0:10000")
    .config("spark.sql.warehouse.dir", "/tmp")
    //.config("spark.ui.enabled", "true")
    //.config("spark.ui.port", "4041")
    .enableHiveSupport()
    .withExtensions(extensionBuilder)
    .getOrCreate()

  def getSession(): SparkSession = {
    spark.sparkContext.setLogLevel("WARN")
    return spark
  }
}
