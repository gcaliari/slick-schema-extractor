package controllers

import play.api.Play.current
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText}
import play.api.i18n.Messages.Implicits._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.{Result, _}
import slick.codegen.SourceCodeGenerator
import slick.dbio
import slick.dbio.Effect.All
import slick.driver.MySQLDriver.api._
import slick.driver.{MySQLDriver, PostgresDriver}
import slick.jdbc.JdbcBackend
import slick.model.Model

import scala.concurrent.Future

case class DbConfig(tablesCsv: String, dbUrl: String, dbUser: String, dbPassword: String, dbDriver: String)

object Application {
  val POSTGRES_DRIVER = "org.postgresql.Driver"
  val MYSQL_DRIVER    = "com.mysql.jdbc.Driver"

}

class Application extends Controller {

  val dbConfigForm: Form[DbConfig] = Form(
    mapping(
      "tablesCsv"   -> nonEmptyText,
      "dbUrl"       -> nonEmptyText,
      "dbUser"      -> nonEmptyText,
      "dbPassword"  -> nonEmptyText,
      "dbDriver"    -> nonEmptyText.verifying( v => Application.POSTGRES_DRIVER == v || Application.MYSQL_DRIVER == v )
    )(DbConfig.apply)(DbConfig.unapply))


  def index = Action { implicit request =>
    Ok(views.html.index(dbConfigForm))
  }

  def codeGen() = Action.async { implicit request =>
    dbConfigForm.bindFromRequest.fold(
      formWithErrors => Future { BadRequest(views.html.index(formWithErrors)) },
      data           => buildSlickSchema(data)
    )
  }



  def buildSlickSchema(dbConfig: DbConfig): Future[Result] = {
    val included: Array[String] = dbConfig.tablesCsv.split(",")

    // fetch data model
    dbConfig.dbDriver match {
      case Application.POSTGRES_DRIVER =>
        val fetchDataModel = PostgresDriver.defaultTables.map(_.filter(t => included contains t.name.name)).flatMap(PostgresDriver.createModelBuilder(_, false).buildModel)
        fetchFrom(dbConfig, fetchDataModel)
      case Application.MYSQL_DRIVER =>
        val fetchDataModel = MySQLDriver.defaultTables.map(_.filter(t => included contains t.name.name)).flatMap(MySQLDriver.createModelBuilder(_, false).buildModel)
        fetchFrom(dbConfig, fetchDataModel)
      case _ => Future { BadRequest }
    }

  }

  def fetchFrom(dbConfig: DbConfig, fetchDataModel: dbio.DBIOAction[Model, NoStream, All with All]): Future[Result] = {
    //    val db: JdbcBackend#DatabaseDef = Database.forConfig("slick.dbs.server-api.db")
    val db: JdbcBackend#DatabaseDef = Database.forURL(dbConfig.dbUrl, dbConfig.dbUser, dbConfig.dbPassword, null, dbConfig.dbDriver)
    val modelFuture = db.run(fetchDataModel)
    // customize code generator
    val codegenFuture = modelFuture.map(model => new SourceCodeGenerator(model) {
      //      // override mapped table and class name
      //      override def entityName =
      //        dbTableName => dbTableName.dropRight(1).toLowerCase.toCamelCase
      //
      //      override def tableName =
      //        dbTableName => dbTableName.toLowerCase.toCamelCase
      //
      //      // add some custom import
      //      override def code = "import foo.{MyCustomType,MyCustomTypeMapper}" + "\n" + super.code
      //
      //      // override table generator
      //      override def Table = new Table(_) {
      //        // disable entity class generation and mapping
      //        override def EntityType = new EntityType {
      //          override def classEnabled = false
      //        }
      //
      //        // override contained column generator
      //        override def Column = new Column(_) {
      //          // use the data model member of this column to change the Scala type,
      //          // e.g. to a custom enum or anything else
      //          override def rawType =
      //            if (model.name == "SOME_SPECIAL_COLUMN_NAME") "MyCustomType" else super.rawType
      //        }
      //      }
    })
    //    codegenFuture.onComplete {
    //      case Success(codegen) => codegen.writeToFile("slick.driver.MySQLDriver", "tmp/", "models")
    //      case Failure(e) => Logger.warn(s"Code generation ended on Failure='${e.getMessage}'")
    //    }
    codegenFuture.map { r =>
      val result = r.code
      db.close()
      Ok(result)
    }
  }
}


