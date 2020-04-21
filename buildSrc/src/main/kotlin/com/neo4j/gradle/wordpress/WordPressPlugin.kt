package com.neo4j.gradle.wordpress

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import com.beust.klaxon.KlaxonException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.yaml.snakeyaml.Yaml
import java.io.FileInputStream
import java.net.URL
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.*


open class WordPressExtension(objects: ObjectFactory) {
  val scheme: Property<String> = objects.property()
  val host: Property<String> = objects.property()
  val username: Property<String> = objects.property()
  val password: Property<String> = objects.property()
}

open class WordPressPlugin : Plugin<Project> {

  override fun apply(target: Project) {
    target.extensions.create("wordpress", WordPressExtension::class.java)
  }
}

data class PaginatedResult<T>(val result: List<T>, val hasNext: Boolean)

data class TaxonomyReference(val slug: String, val id: Int)

data class TaxonomyEndpoint(val slug: String, val endpoint: String)

data class Taxonomy(val key: String, val values: List<String>)

data class DocumentAttributes(val slug: String,
                              val title: String,
                              val tags: List<String>,
                              val taxonomies: List<Taxonomy>,
                              val content: String,
                              val parentPath: String?)

abstract class WordPressUploadTask : DefaultTask() {

  @InputFiles
  var sources: MutableList<ConfigurableFileTree> = mutableListOf()

  @Input
  var type: String = ""

  @Input
  // publish, future, draft, pending, private
  var status: String = "draft"

  @Input
  var scheme: String = "https"

  @Input
  var host: String = ""

  @Input
  var username: String = ""

  @Input
  var password: String = ""

  @Input
  var template: String = ""

  @TaskAction
  fun task() {
    if (type.isBlank()) {
      logger.error("The type is mandatory, aborting...")
      return
    }
    val wordPressUpload = WordPressUpload(
      documentType = WordPressDocumentType(type),
      documentStatus = status,
      documentTemplate = template,
      sources = sources,
      connectionInfo = wordPressConnectionInfo(),
      logger = logger
    )
    wordPressUpload.publish()
  }

  private fun wordPressConnectionInfo(): WordPressConnectionInfo {
    val wordPressExtension = project.extensions.findByType(WordPressExtension::class.java)
    val hostValue = wordPressExtension?.host?.getOrElse(host) ?: host
    val schemeValue = wordPressExtension?.scheme?.getOrElse(scheme) ?: scheme
    val usernameValue = wordPressExtension?.username?.getOrElse(username) ?: username
    val passwordValue = wordPressExtension?.password?.getOrElse(password) ?: password
    return WordPressConnectionInfo(
      scheme = schemeValue,
      host = hostValue,
      username = usernameValue,
      password = passwordValue
    )
  }

  fun setSource(sources: FileCollection) {
    sources.forEach {
      this.sources.add(project.fileTree(it))
    }
  }

  fun setSource(source: String) {
    this.sources.add(project.fileTree(source))
  }

  fun setSource(vararg sources: String?) {
    sources.forEach {
      if (it != null) {
        this.sources.add(project.fileTree(it))
      }
    }
  }

  fun setSource(sources: List<String>) {
    sources.forEach {
      this.sources.add(project.fileTree(it))
    }
  }

  fun setSource(source: ConfigurableFileTree) {
    this.sources.add(source)
  }
}

data class WordPressConnectionInfo(val scheme: String,
                                   val host: String,
                                   val username: String,
                                   val password: String,
                                   val connectTimeout: Duration = Duration.ofSeconds(10),
                                   val writeTimeout: Duration = Duration.ofSeconds(10),
                                   val readTimeout: Duration = Duration.ofSeconds(30))

data class WordPressDocumentType(val name: String) {
  val urlPath: String = when (name) {
    // type is singular but endpoint is plural for built-in types post and page.
    "post" -> "posts"
    "page" -> "pages"
    else -> name
  }
}

data class WordPressDocument(val id: Int,
                             val slug: String,
                             val type: WordPressDocumentType)

internal class WordPressUpload(val documentType: WordPressDocumentType,
                               val documentStatus: String,
                               val documentTemplate: String,
                               val sources: MutableList<ConfigurableFileTree>,
                               val connectionInfo: WordPressConnectionInfo,
                               val logger: Logger) {

  private val yaml = Yaml()
  private val klaxon = Klaxon()
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
  private val httpClient = httpClient()
  private fun baseUrlBuilder() = HttpUrl.Builder()
    .scheme(connectionInfo.scheme)
    .host(connectionInfo.host)
    .addPathSegment("wp-json")
    .addPathSegment("wp")
    .addPathSegment("v2")

  fun publish(): Boolean {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    formatter.format(Date())
    val date = formatter.format(Date())
    val documentsWithAttributes = getDocumentsWithAttributes()
    if (documentsWithAttributes.isEmpty()) {
      logger.info("No file to upload")
      return false
    }
    val slugs = documentsWithAttributes.map { it.slug }
    val searchUrl = baseUrlBuilder()
      .addPathSegment(documentType.urlPath)
      .addQueryParameter("per_page", slugs.size.toString())
      .addQueryParameter("slug", slugs.joinToString(","))
      .addQueryParameter("status", "publish,future,draft,pending,private")
      .build()
    val credential = Credentials.basic(connectionInfo.username, connectionInfo.password)
    val searchRequest = Request.Builder()
      .url(searchUrl)
      // force the header because WordPress returns a 400 instead of a 401 when the authentication fails...
      .header("Authorization", credential)
      .get()
      .build()
    val wordPressDocumentsBySlug = executeRequest(searchRequest) { responseBody ->
      try {
        val jsonArray = klaxon.parseJsonArray(responseBody.charStream())
        jsonArray.value.mapNotNull { item ->
          if (item is JsonObject) {
            val slug = item.string("slug")!!
            slug to WordPressDocument(item.int("id")!!, slug, documentType)
          } else {
            null
          }
        }.toMap()
      } catch (e: KlaxonException) {
        logger.error("Unable to parse the response", e)
        null
      }
    } ?: return false
    val parentIdsByPath = mutableMapOf<String, WordPressDocument?>()
    val taxonomyReferencesBySlug = if (documentType.name !== "page") {
      // taxonomies cannot be assigned on a page
      val taxonomyEndpoints = getTaxonomyEndpoints().map {
        it.slug to it
      }.toMap()
      val taxonomySlugs = documentsWithAttributes.flatMap {
        it.taxonomies.map { taxonomy -> taxonomy.key }
      }.toSet()
     taxonomySlugs.mapNotNull { taxonomySlug ->
        val taxonomyEndpoint = taxonomyEndpoints[taxonomySlug]
        if (taxonomyEndpoint == null) {
          logger.warn("Taxonomy: $taxonomySlug does not exist, unable to set this taxonomy on posts")
          null
        } else {
          val taxonomyReferences = getTaxonomyReferences(taxonomyEndpoint)
          taxonomySlug to taxonomyReferences.map { it.slug to it.id }.toMap()
        }
      }.toMap()
    } else {
      emptyMap()
    }
    for (documentAttributes in documentsWithAttributes) {
      val data = mutableMapOf<String, Any>(
        "date_gmt" to date,
        "slug" to documentAttributes.slug,
        "status" to documentStatus,
        "title" to documentAttributes.title,
        "content" to documentAttributes.content,
        // fixme: expect a list of ids "{"tags":"tags[0] is not of type integer."}"
        //"tags" to documentAttributes.tags,
        "type" to documentType
      )
      for (taxonomy in documentAttributes.taxonomies) {
        val values = taxonomy.values.mapNotNull { value ->
          val taxonomyReference = taxonomyReferencesBySlug[taxonomy.key]?.get(value)
          if (taxonomyReference == null) {
            logger.warn("Unable to resolve taxonomy id for ${taxonomy.key}/$value on post ${documentAttributes.slug}")
            null
          } else {
            taxonomyReference
          }
        }
        data[taxonomy.key] = values
      }
      val parentPath = documentAttributes.parentPath
      if (documentType == WordPressDocumentType("page") && parentPath != null) {
        val parentPage = if (parentIdsByPath.containsKey(parentPath)) {
          parentIdsByPath[parentPath]
        } else {
          val parentPage = findParentPage(parentPath, credential)
          parentIdsByPath[parentPath] = parentPage
          parentPage
        }
        if (parentPage == null) {
          logger.warn("No page found for path: $parentPath, unable to publish ${documentAttributes.slug} to WordPress")
          continue
        }
        data["parent"] = parentPage.id
      }
      if (documentTemplate.isNotBlank()) {
        data["template"] = documentTemplate
      }
      logger.info("data: $data")
      val wordPressDocument = wordPressDocumentsBySlug[documentAttributes.slug]
      if (wordPressDocument != null) {
        // document already exists on WordPress, updating...
        updateDocument(data, wordPressDocument)
      } else {
        // document does not exist on WordPress, creating...
        createDocument(data)
      }
    }
    return true
  }

  private fun getTaxonomyReferences(taxonomyEndpoint: TaxonomyEndpoint): List<TaxonomyReference> {
    val baseUrl = baseUrlBuilder()
      .addPathSegment(taxonomyEndpoint.endpoint)
      .build()
    return getRecursiveObjects(baseUrl = baseUrl) { result ->
      result.mapNotNull {
        if (it is JsonObject) {
          TaxonomyReference(it["slug"] as String, it["id"] as Int)
        } else {
          null
        }
      }
    }
  }

  private fun getTaxonomyEndpoints(): List<TaxonomyEndpoint> {
    val searchUrl = baseUrlBuilder()
      .addPathSegment("taxonomies")
      .build()
    val credential = Credentials.basic(connectionInfo.username, connectionInfo.password)
    val searchRequest = Request.Builder()
      .url(searchUrl)
      // force the header because WordPress returns a 400 instead of a 401 when the authentication fails...
      .header("Authorization", credential)
      .get()
      .build()
    return executeRequest(searchRequest) { responseBody ->
      try {
        resolveTaxonomyEndpoints(klaxon.parseJsonObject(responseBody.charStream()))
      } catch (e: KlaxonException) {
        logger.error("Unable to parse the response", e)
        null
      }
    }.orEmpty()
  }

  private fun resolveTaxonomyEndpoints(taxonomies: JsonObject): List<TaxonomyEndpoint> {
    return taxonomies.keys.mapNotNull { key ->
      val taxonomyObject = taxonomies[key]
      if (taxonomyObject is JsonObject) {
        val types = taxonomyObject["types"]
        if (types is JsonArray<*>) {
          if(types.value.contains(documentType.name)) {
            TaxonomyEndpoint(taxonomyObject.getValue("slug") as String, taxonomyObject.getValue("rest_base") as String)
          } else {
            null
          }
        } else {
          null
        }
      } else {
        null
      }
    }
  }

  private fun findParentPage(parentPath: String, credential: String): WordPressDocument? {
    // slug is the last part of the path
    // example:
    // - path: "/docs/labs/"
    // - slug: "labs"
    // the path must _not_ contain the complete URL (ie. "https://neo4j.com/docs/labs")
    val parentSlug = parentPath
      .removeSuffix("/")
      .split("/")
      .last { it.isNotEmpty() }
    val searchParentUrl = baseUrlBuilder()
      .addPathSegment(documentType.urlPath)
      .addQueryParameter("per_page", "10")
      .addQueryParameter("slug", parentSlug)
      .addQueryParameter("status", "publish,future,draft,pending,private")
      .build()
    val searchParentRequest = Request.Builder()
      .url(searchParentUrl)
      // force the header because WordPress returns a 400 instead of a 401 when the authentication fails...
      .header("Authorization", credential)
      .get()
      .build()
    return executeRequest(searchParentRequest) { responseBody ->
      try {
        val jsonArray = klaxon.parseJsonArray(responseBody.charStream())
        val documents = jsonArray.value.mapNotNull { item ->
          if (item is JsonObject) {
            val link = item.string("link")
            // extract the path part from the URL
            val linkPath = URL(link).path.removeSuffix("/")
            val parentLinkPath = parentPath.removeSuffix("/")
            if (linkPath == parentLinkPath) {
              val slug = item.string("slug")!!
              WordPressDocument(item.int("id")!!, slug, documentType)
            } else {
              null
            }
          } else {
            null
          }
        }
        documents.firstOrNull()
      } catch (e: KlaxonException) {
        logger.error("Unable to parse the response", e)
        null
      }
    }
  }

  private fun updateDocument(data: MutableMap<String, Any>, wordPressDocument: WordPressDocument): Boolean {
    data["id"] = wordPressDocument.id
    val url = baseUrlBuilder()
      .addPathSegment(documentType.urlPath)
      .addPathSegment(wordPressDocument.id.toString())
      .build()
    logger.debug("POST $url")
    val updateRequest = Request.Builder()
      .url(url)
      .post(klaxon.toJsonString(data).toRequestBody(jsonMediaType))
      .build()
    return executeRequest(updateRequest) { responseBody ->
      try {
        val jsonObject = klaxon.parseJsonObject(responseBody.charStream())
        val id = jsonObject.int("id")!!
        logger.quiet("Successfully updated the ${documentType.name.toLowerCase()} with id: $id and slug: ${data["slug"]}")
        true
      } catch (e: KlaxonException) {
        logger.error("Unable to parse the response for the ${documentType.name.toLowerCase()} with slug: ${data["slug"]}", e)
        false
      }
    } ?: false
  }

  private fun createDocument(data: MutableMap<String, Any>): Boolean {
    val url = baseUrlBuilder()
      .addPathSegment(documentType.urlPath)
      .build()
    logger.debug("POST $url")
    val createRequest = Request.Builder()
      .url(url)
      .post(klaxon.toJsonString(data).toRequestBody(jsonMediaType))
      .build()
    return executeRequest(createRequest) { responseBody ->
      try {
        val jsonObject = klaxon.parseJsonObject(responseBody.charStream())
        val id = jsonObject.int("id")!!
        logger.quiet("Successfully created a new ${documentType.name.toLowerCase()} with id: $id and slug: ${data["slug"]}")
        true
      } catch (e: KlaxonException) {
        logger.error("Unable to parse the response for the new ${documentType.name.toLowerCase()} with slug: ${data["slug"]}", e)
        false
      }
    } ?: false
  }

  /**
   * Get a list of documents with attributes (read from a YAML file).
   * The YAML file is generated in a pre-task.
   */
  private fun getDocumentsWithAttributes(): List<DocumentAttributes> {
    return sources
      .flatten()
      .filter { it.extension == "html" }
      .mapNotNull { file ->
        val yamlFile = Paths.get(file.toPath().parent.toString(), "${file.nameWithoutExtension}.yml").toFile()
        val fileName = file.name
        val yamlFileAbsolutePath = yamlFile.absolutePath
        if (!yamlFile.exists()) {
          logger.warn("Missing YAML file: $yamlFileAbsolutePath, unable to publish $fileName to WordPress")
          null
        } else {
          logger.debug("Loading $yamlFile")
          val attributes = yaml.load(FileInputStream(yamlFile)) as Map<*, *>
          logger.debug("Document attributes in the YAML file: $attributes")
          val slug = getSlug(attributes, yamlFileAbsolutePath, fileName)
          val title = getTitle(attributes, yamlFileAbsolutePath, fileName)
          if (slug != null && title != null) {
            // The terms assigned to the object in the post_tag taxonomy.
            val tags = getTags(attributes)
            val taxonomies = getTaxonomies(attributes)
            val parentPath = getParentPath(attributes)
            DocumentAttributes(slug, title, tags, taxonomies, file.readText(Charsets.UTF_8), parentPath)
          } else {
            null
          }
        }
      }
  }

  /**
   * Execute a request that returns JSON.
   */
  private fun <T> executeRequest(request: Request, mapper: (ResponseBody) -> T): T? {
    httpClient.newCall(request).execute().use {
      if (it.isSuccessful) {
        it.body.use { responseBody ->
          if (responseBody != null) {
            val contentType = responseBody.contentType()
            if (contentType != null) {
              if (contentType.type == "application" && contentType.subtype == "json") {
                try {
                  return mapper(responseBody)
                } catch (e: KlaxonException) {
                  logger.error("Unable to parse the response", e)
                }
              } else {
                logger.warn("Content-Type must be application/json")
              }
            } else {
              logger.warn("Content-Type is undefined")
            }
          } else {
            logger.warn("Response is empty")
          }
        }
      } else {
        logger.warn("Request is unsuccessful - {request: $request, code: ${it.code}, message: ${it.message}, response: ${it.body?.string()}}")
      }
    }
    return null
  }

  fun <T> getRecursiveObjects(page: Int = 1, acc: List<T> = emptyList(), baseUrl: HttpUrl, mapper: (response: JsonArray<*>) -> List<T>): List<T> {
    val searchUrl = baseUrl
      .newBuilder()
      .addQueryParameter("page", page.toString())
      .addQueryParameter("per_page", "100")
      .build()
    val credential = Credentials.basic(connectionInfo.username, connectionInfo.password)
    val searchRequest = Request.Builder()
      .url(searchUrl)
      // force the header because WordPress returns a 400 instead of a 401 when the authentication fails...
      .header("Authorization", credential)
      .get()
      .build()
    val paginatedResult = httpClient.newCall(searchRequest).execute().use { response ->
      val totalPages = response.header("X-WP-TotalPages", "1")?.toInt() ?: 1
      val hasNext = totalPages > page
      response.body.use {
        if (it != null) {
          PaginatedResult(mapper(klaxon.parseJsonArray(it.charStream())), hasNext)
        } else {
          PaginatedResult(emptyList(), hasNext)
        }
      }
    }
    return if (paginatedResult.hasNext) {
      getRecursiveObjects(page + 1, acc + paginatedResult.result, baseUrl, mapper = mapper)
    } else {
      acc + paginatedResult.result
    }
  }

  private fun getMandatoryString(attributes: Map<*, *>, name: String, yamlFilePath: String, fileName: String): String? {
    val value = attributes[name]
    if (value == null) {
      logger.warn("No $name found in: $yamlFilePath, unable to publish $fileName to WordPress")
      return null
    }
    if (value !is String) {
      logger.warn("$name must be a String in: $yamlFilePath, unable to publish $fileName to WordPress")
      return null
    }
    if (value.isBlank()) {
      logger.warn("$name must not be blank in: $yamlFilePath, unable to publish $fileName to WordPress")
      return null
    }
    return value
  }

  private fun getTaxonomies(attributes: Map<*, *>): List<Taxonomy> {
    val value = attributes["taxonomies"] ?: return listOf()
    return if (value is List<*>) {
      value.mapNotNull { info ->
        if (info is Map<*, *>) {
          @Suppress("UNCHECKED_CAST")
          Taxonomy(info["key"] as String, info["values"] as List<String>)
        } else {
          null
        }
      }
    } else {
      listOf()
    }
  }

  private fun getTags(attributes: Map<*, *>): List<String> {
    val value = attributes["tags"] ?: return listOf()
    if (value is List<*>) {
      return value.filterIsInstance<String>()
    }
    return listOf()
  }

  private fun getParentPath(attributes: Map<*, *>): String? {
    val name = "parent_path"
    val value = attributes[name] ?: return null
    if (value !is String) {
      return null
    }
    if (value.isBlank()) {
      return null
    }
    return value
  }

  private fun getTitle(attributes: Map<*, *>, yamlFilePath: String, fileName: String): String? {
    return getMandatoryString(attributes, "title", yamlFilePath, fileName)
  }

  private fun getSlug(attributes: Map<*, *>, yamlFilePath: String, fileName: String): String? {
    return getMandatoryString(attributes, "slug", yamlFilePath, fileName)
  }

  private fun httpClient(): OkHttpClient {
    val client = OkHttpClient.Builder()
      .authenticator(object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
          if (responseCount(response) >= 3) {
            return null // unable to authenticate for the third time, we give up...
          }
          val credential = Credentials.basic(connectionInfo.username, connectionInfo.password)
          return response.request.newBuilder().header("Authorization", credential).build()
        }
      })
      .connectTimeout(connectionInfo.connectTimeout)
      .writeTimeout(connectionInfo.writeTimeout)
      .readTimeout(connectionInfo.readTimeout)
    return client.build()
  }

  private fun responseCount(response: Response): Int {
    var count = 1
    var res = response.priorResponse
    while (res != null) {
      count++
      res = res.priorResponse
    }
    return count
  }
}
