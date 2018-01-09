package net.anopara

import akka.actor.ActorSystem
import akka.util.ByteString
import colossus.IOSystem
import colossus.core.{InitContext, ServerContext}
import colossus.protocols.http.HttpMethod._
import colossus.protocols.http.UrlParsing._
import colossus.protocols.http.{Http, HttpHeader, HttpHeaders, HttpServer, Initializer, RequestHandler}
import colossus.service.Callback
import colossus.service.GenRequestHandler.PartialHandler
import com.typesafe.config.ConfigFactory
import io.undertow.server.handlers.PathHandler
import io.undertow.{Handlers, Undertow}
import io.undertow.server.handlers.resource.ClassPathResourceManager
import io.undertow.util.Headers
import net.anopara.model.asset.AssetsResource
import net.anopara.model.db.Repository
import net.anopara.model.{Renderer, SashimiSettings}
import org.flywaydb.core.Flyway

object Sashimi {
  def start(settings: SashimiSettings) = {
    val flyway = new Flyway()
    val config = ConfigFactory.load.getConfig("ctx.dataSource")
    flyway.setDataSource(config.getString("url"), config.getString("user"), config.getString("password"))
    flyway.migrate()

    val repository = new Repository(settings.postCacheTime)
    val renderer = new Renderer(settings.pageTemplate)

    val server = Undertow.builder()
      .addHttpListener(settings.port, "localhost")
      .setHandler(Handlers.path()
        .addPrefixPath("/assets", Handlers.resource(new ClassPathResourceManager(Thread.currentThread().getContextClassLoader, "static")))
        .addExactPath("/", x => {
          x.getResponseHeaders.put(Headers.CONTENT_TYPE, "text/plain")
          x.getResponseSender.send("Hello")
        })
      ).build()
    server.start()
  }
}

class SashimiInitializer(context: InitContext)(handler: ServerContext => SashimiRequestHandler) extends Initializer(context) {
  override def onConnect = ctx => handler(ctx)
}

class SashimiRequestHandler(context: ServerContext,
  settings: SashimiSettings,
  repository: Repository,
  renderer: Renderer,
  assets: AssetsResource,
) extends RequestHandler(context) {
  override def handle: PartialHandler[Http] = {
    case request @ Get on Root / "hello" => {
      Callback.successful(request.ok("Hello World!"))
    }

    case request @ Get on Root / Integer(year) / Integer(month) / Integer(day) / postName => {
      repository.getPost(year, month, day, postName) match {
        case Some(p) =>
          Callback.successful(request.ok(renderer.renderPage(p), HttpHeaders(HttpHeader("Content-Type", "text/html"))))
        case None =>
          Callback.successful(request.notFound("Not Found")) // TODO need template for 404
      }
    }

    case request @ Get on Root / pageName =>
      repository.getPage(pageName) match {
        case Some(p) =>
          Callback.successful(request.ok(renderer.renderPage(p), HttpHeaders(HttpHeader("Content-Type", "text/html"))))
        case None =>
          Callback.successful(request.notFound("Not Found")) // TODO need template for 404
      }

    case x => assetHandler(x)
  }

  import colossus.protocols.http.UrlParsing.Strings._
  val assetHandler: PartialHandler[Http] = {
    case request @ Get on Root /: "assets" /: path =>
      assets.getFromResource(path) match {
        case None =>
          Callback.successful(request.notFound("Not Found"))
        case Some(a) => Callback.successful(
          request.ok(
            ByteString(Stream.continually(a.stream.read()).takeWhile(_ != -1).map(_.toByte).toArray),
            HttpHeaders(HttpHeader("Content-Type", a.mimeType))
          )
        )
      }
  }
}