/*
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

package org.apache.spark.sql.hive.execution

import org.apache.hadoop.fs.Path

import org.apache.spark.sql.{AnalysisException, QueryTest, SaveMode}
import org.apache.spark.sql.catalyst.catalog.CatalogTableType
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.hive.test.TestHiveSingleton
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SQLTestUtils

class HiveDDLSuite extends QueryTest with SQLTestUtils with TestHiveSingleton {
  import hiveContext.implicits._

  // check if the directory for recording the data of the table exists.
  private def tableDirectoryExists(tableIdentifier: TableIdentifier): Boolean = {
    val expectedTablePath =
      hiveContext.sessionState.catalog.hiveDefaultTableFilePath(tableIdentifier)
    val filesystemPath = new Path(expectedTablePath)
    val fs = filesystemPath.getFileSystem(sparkContext.hadoopConfiguration)
    fs.exists(filesystemPath)
  }

  test("drop tables") {
    withTable("tab1") {
      val tabName = "tab1"

      assert(!tableDirectoryExists(TableIdentifier(tabName)))
      sql(s"CREATE TABLE $tabName(c1 int)")

      assert(tableDirectoryExists(TableIdentifier(tabName)))
      sql(s"DROP TABLE $tabName")

      assert(!tableDirectoryExists(TableIdentifier(tabName)))
      sql(s"DROP TABLE IF EXISTS $tabName")
      sql(s"DROP VIEW IF EXISTS $tabName")
    }
  }

  test("drop managed tables") {
    withTempDir { tmpDir =>
      val tabName = "tab1"
      withTable(tabName) {
        assert(tmpDir.listFiles.isEmpty)
        sql(
          s"""
             |create table $tabName
             |stored as parquet
             |location '$tmpDir'
             |as select 1, '3'
          """.stripMargin)

        val hiveTable =
          hiveContext.sessionState.catalog
            .getTableMetadata(TableIdentifier(tabName, Some("default")))
        // It is a managed table, although it uses external in SQL
        assert(hiveTable.tableType == CatalogTableType.MANAGED_TABLE)

        assert(tmpDir.listFiles.nonEmpty)
        sql(s"DROP TABLE $tabName")
        // The data are deleted since the table type is not EXTERNAL
        assert(tmpDir.listFiles == null)
      }
    }
  }

  test("drop external data source table") {
    withTempDir { tmpDir =>
      val tabName = "tab1"
      withTable(tabName) {
        assert(tmpDir.listFiles.isEmpty)

        withSQLConf(SQLConf.PARQUET_WRITE_LEGACY_FORMAT.key -> "true") {
          Seq(1 -> "a").toDF("i", "j")
            .write
            .mode(SaveMode.Overwrite)
            .format("parquet")
            .option("path", tmpDir.toString)
            .saveAsTable(tabName)
        }

        val hiveTable =
          hiveContext.sessionState.catalog
            .getTableMetadata(TableIdentifier(tabName, Some("default")))
        // This data source table is external table
        assert(hiveTable.tableType == CatalogTableType.EXTERNAL_TABLE)

        assert(tmpDir.listFiles.nonEmpty)
        sql(s"DROP TABLE $tabName")
        // The data are not deleted since the table type is EXTERNAL
        assert(tmpDir.listFiles.nonEmpty)
      }
    }
  }

  test("create table and view with comment") {
    val catalog = hiveContext.sessionState.catalog
    val tabName = "tab1"
    withTable(tabName) {
      sql(s"CREATE TABLE $tabName(c1 int) COMMENT 'BLABLA'")
      val viewName = "view1"
      withView(viewName) {
        sql(s"CREATE VIEW $viewName COMMENT 'no comment' AS SELECT * FROM $tabName")
        val tableMetadata = catalog.getTableMetadata(TableIdentifier(tabName, Some("default")))
        val viewMetadata = catalog.getTableMetadata(TableIdentifier(viewName, Some("default")))
        assert(tableMetadata.properties.get("comment") == Option("BLABLA"))
        assert(viewMetadata.properties.get("comment") == Option("no comment"))
      }
    }
  }

  test("drop views") {
    withTable("tab1") {
      val tabName = "tab1"
      sqlContext.range(10).write.saveAsTable("tab1")
      withView("view1") {
        val viewName = "view1"

        assert(tableDirectoryExists(TableIdentifier(tabName)))
        assert(!tableDirectoryExists(TableIdentifier(viewName)))
        sql(s"CREATE VIEW $viewName AS SELECT * FROM tab1")

        assert(tableDirectoryExists(TableIdentifier(tabName)))
        assert(!tableDirectoryExists(TableIdentifier(viewName)))
        sql(s"DROP VIEW $viewName")

        assert(tableDirectoryExists(TableIdentifier(tabName)))
        sql(s"DROP VIEW IF EXISTS $viewName")
      }
    }
  }

  test("alter views - rename") {
    val tabName = "tab1"
    withTable(tabName) {
      sqlContext.range(10).write.saveAsTable(tabName)
      val oldViewName = "view1"
      val newViewName = "view2"
      withView(oldViewName, newViewName) {
        val catalog = hiveContext.sessionState.catalog
        sql(s"CREATE VIEW $oldViewName AS SELECT * FROM $tabName")

        assert(catalog.tableExists(TableIdentifier(oldViewName)))
        assert(!catalog.tableExists(TableIdentifier(newViewName)))
        sql(s"ALTER VIEW $oldViewName RENAME TO $newViewName")
        assert(!catalog.tableExists(TableIdentifier(oldViewName)))
        assert(catalog.tableExists(TableIdentifier(newViewName)))
      }
    }
  }

  test("alter views - set/unset tblproperties") {
    val tabName = "tab1"
    withTable(tabName) {
      sqlContext.range(10).write.saveAsTable(tabName)
      val viewName = "view1"
      withView(viewName) {
        val catalog = hiveContext.sessionState.catalog
        sql(s"CREATE VIEW $viewName AS SELECT * FROM $tabName")

        assert(catalog.getTableMetadata(TableIdentifier(viewName))
          .properties.filter(_._1 != "transient_lastDdlTime") == Map())
        sql(s"ALTER VIEW $viewName SET TBLPROPERTIES ('p' = 'an')")
        assert(catalog.getTableMetadata(TableIdentifier(viewName))
          .properties.filter(_._1 != "transient_lastDdlTime") == Map("p" -> "an"))

        // no exception or message will be issued if we set it again
        sql(s"ALTER VIEW $viewName SET TBLPROPERTIES ('p' = 'an')")
        assert(catalog.getTableMetadata(TableIdentifier(viewName))
          .properties.filter(_._1 != "transient_lastDdlTime") == Map("p" -> "an"))

        // the value will be updated if we set the same key to a different value
        sql(s"ALTER VIEW $viewName SET TBLPROPERTIES ('p' = 'b')")
        assert(catalog.getTableMetadata(TableIdentifier(viewName))
          .properties.filter(_._1 != "transient_lastDdlTime") == Map("p" -> "b"))

        sql(s"ALTER VIEW $viewName UNSET TBLPROPERTIES ('p')")
        assert(catalog.getTableMetadata(TableIdentifier(viewName))
          .properties.filter(_._1 != "transient_lastDdlTime") == Map())

        val message = intercept[AnalysisException] {
          sql(s"ALTER VIEW $viewName UNSET TBLPROPERTIES ('p')")
        }.getMessage
        assert(message.contains(
          "attempted to unset non-existent property 'p' in table '`view1`'"))
      }
    }
  }

  test("alter views and alter table - misuse") {
    val tabName = "tab1"
    withTable(tabName) {
      sqlContext.range(10).write.saveAsTable(tabName)
      val oldViewName = "view1"
      val newViewName = "view2"
      withView(oldViewName, newViewName) {
        val catalog = hiveContext.sessionState.catalog
        sql(s"CREATE VIEW $oldViewName AS SELECT * FROM $tabName")

        assert(catalog.tableExists(TableIdentifier(tabName)))
        assert(catalog.tableExists(TableIdentifier(oldViewName)))

        var message = intercept[AnalysisException] {
          sql(s"ALTER VIEW $tabName RENAME TO $newViewName")
        }.getMessage
        assert(message.contains(
          "Cannot alter a table with ALTER VIEW. Please use ALTER TABLE instead"))

        message = intercept[AnalysisException] {
          sql(s"ALTER VIEW $tabName SET TBLPROPERTIES ('p' = 'an')")
        }.getMessage
        assert(message.contains(
          "Cannot alter a table with ALTER VIEW. Please use ALTER TABLE instead"))

        message = intercept[AnalysisException] {
          sql(s"ALTER VIEW $tabName UNSET TBLPROPERTIES ('p')")
        }.getMessage
        assert(message.contains(
          "Cannot alter a table with ALTER VIEW. Please use ALTER TABLE instead"))

        message = intercept[AnalysisException] {
          sql(s"ALTER TABLE $oldViewName RENAME TO $newViewName")
        }.getMessage
        assert(message.contains(
          "Cannot alter a view with ALTER TABLE. Please use ALTER VIEW instead"))

        message = intercept[AnalysisException] {
          sql(s"ALTER TABLE $oldViewName SET TBLPROPERTIES ('p' = 'an')")
        }.getMessage
        assert(message.contains(
          "Cannot alter a view with ALTER TABLE. Please use ALTER VIEW instead"))

        message = intercept[AnalysisException] {
          sql(s"ALTER TABLE $oldViewName UNSET TBLPROPERTIES ('p')")
        }.getMessage
        assert(message.contains(
          "Cannot alter a view with ALTER TABLE. Please use ALTER VIEW instead"))

        assert(catalog.tableExists(TableIdentifier(tabName)))
        assert(catalog.tableExists(TableIdentifier(oldViewName)))
      }
    }
  }

  test("drop table using drop view") {
    withTable("tab1") {
      sql("CREATE TABLE tab1(c1 int)")
      val message = intercept[AnalysisException] {
        sql("DROP VIEW tab1")
      }.getMessage
      assert(message.contains("Cannot drop a table with DROP VIEW. Please use DROP TABLE instead"))
    }
  }

  test("drop view using drop table") {
    withTable("tab1") {
      sqlContext.range(10).write.saveAsTable("tab1")
      withView("view1") {
        sql("CREATE VIEW view1 AS SELECT * FROM tab1")
        val message = intercept[AnalysisException] {
          sql("DROP TABLE view1")
        }.getMessage
        assert(message.contains("Cannot drop a view with DROP TABLE. Please use DROP VIEW instead"))
      }
    }
  }
}
