import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.*


/**
 * Created by Neverland on 18.01.2018.
 */

object Project : Table() {
    var id = integer("id").primaryKey().autoIncrement()
    var lastDownloadDate = long("last_download_date").nullable()
    var url = varchar("url", 250)
    var name = varchar("name", 250)
    var downloadedProjectVersionId=(integer("downloaded_project_version_id") ).nullable() //references ProjectVersion.id
    //var downloadedPushedAt = long("downloaded_pushed_at")
}

object ProjectVersion : Table() {
    var id = integer("id").primaryKey().autoIncrement()
    var projectId = integer("project_id") references Project.id
    //var pushedAt = long("pushed_at")
    var versionHash=varchar("version_hash", 250)
}

/*object ProjectLibrary : Table() {
    var id = integer("id").primaryKey().autoIncrement()
    var status = bool("status")
    var projectVersionId = integer("project_version_id") references ProjectVersion.id
    var libraryVersionId = integer("library_version_id") references LibraryVersion.id
}

object LibraryVersion : Table() {
    var id = integer("id").primaryKey().autoIncrement()
    var libraryId = integer("library_id") references Library.id
    var version = varchar("version", 250)
}

object Library : Table() {
    var id = integer("id").primaryKey().autoIncrement()
    var name = varchar("name", 250)
    var groupName = varchar("group_name", 250)
}*/

fun main(args: Array<String>) {

    println("repoMinerDownloader: I'm starting now...")

    val jdbc = "jdbc:mysql://127.0.0.1:3306/repo_miner_store?serverTimezone=UTC" //TODO: вынести опции в конфигурацию
    val driver = "com.mysql.cj.jdbc.Driver"

    val dbConnection = Database.connect(jdbc,user="root", password = "4069043", driver = driver)

    transaction {
        logger.addLogger(StdOutSqlLogger)
        /*create(Project)
        println(Project.createStatement())
        println(ProjectVersion.createStatement())*/  //TODO: отладка, сейчас создано перенесением (без коррекции) вручную
        //create(ProjectVersion)
        //create(Library)
        //create(LibraryVersion)
        //create(ProjectLibrary)

    }

    val DOWNLOAD_TASKS_QUEUE_NAME = "repositoryDownloadTasksQueue";
    val RESPONSE_QUEUE_NAME = "responseQueue";

    val factory = ConnectionFactory()
    factory.host = "localhost"
    val brokerConnection = factory.newConnection()
    val downloadTasksChannel = brokerConnection.createChannel()

    val responseChannel = brokerConnection.createChannel()

    //val downloadTasksQueue=

    val args = HashMap<String, Any>()
    args.put("x-max-length", 100)

    val downloadQueue=downloadTasksChannel.queueDeclare( DOWNLOAD_TASKS_QUEUE_NAME, false, false, false, args)

    val responseQueue=responseChannel.queueDeclare( RESPONSE_QUEUE_NAME, false, false, false, null)

    println(" [*] Waiting for messages. To exit press CTRL+C")
    var numberOfJavaRepositories = 0

    var allIterationsCounter=0
    //var trigger=false

    val consumer = object : DefaultConsumer(downloadTasksChannel) {
        @Throws(IOException::class)
        override fun handleDelivery(consumerTag: String, envelope: Envelope,
                                    properties: AMQP.BasicProperties, body: ByteArray) {
            allIterationsCounter++;
            println(downloadQueue.messageCount)

/*            if (downloadQueue.messageCount == 100)
            trigger=true*/


            println("$numberOfJavaRepositories iteration")
            val message = String(body, Charset.forName("UTF-8"))
            if (message == "stop") {
                downloadTasksChannel.close()
                responseChannel.close()
                brokerConnection.close()
                println("$numberOfJavaRepositories were recognized.")
            } else {

                val downloadUrl = message;

                println(downloadUrl)

                val projectName = downloadUrl.substringBeforeLast("/").substringAfterLast("/")

                println(projectName)

                val client = OkHttpClient()
                val request = Request.Builder().url(downloadUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {                                   //TODO: pretty rough
                    println("Unsuccessful response: "+ response)
                    //downloadTasksChannel.close()
                    //brokerConnection.close()
                    //throw IOException("Failed to download file: " + response)
                } else {

                    val fileOutputStream = FileOutputStream("E:/MinedProjects" + "/" + projectName + ".zip")     //TODO: configure - whether save to File System or database
                    fileOutputStream.write(response.body()!!.bytes())
                    fileOutputStream.close()

                    transaction {
                        logger.addLogger(StdOutSqlLogger)

                        val project=Project.insert {
                             it[name] = projectName
                             it[url] = downloadUrl.substringBeforeLast("/")
                             it[lastDownloadDate] = System.currentTimeMillis()
                        }

                        val projectVersion=ProjectVersion.insert {
                            it[projectId] = project[id]
                            it[versionHash] = downloadUrl.substringAfterLast("-")
                        }
                        
                        Project.update({Project.id eq project[Project.id]}){
                            it[downloadedProjectVersionId]=projectVersion[id]
                        }

                    }

                    numberOfJavaRepositories++

                }

               /* if ((downloadQueue.messageCount == 0)&&
                        trigger){
                    responseChannel.basicPublish("", RESPONSE_QUEUE_NAME, null, "consumed".toByteArray())
                    trigger=false
                }*/
            }

        }
    }
    downloadTasksChannel.basicConsume(DOWNLOAD_TASKS_QUEUE_NAME, true, consumer)

}