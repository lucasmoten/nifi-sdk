package com.deciphernow.greymatter.data.nifi.http

import io.circe.{Decoder, Printer}
import io.circe.fs2._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.{Headers, Method, Request, Response, Uri}
import org.http4s.multipart.{Multipart, Part}
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.parser.decode
import cats.implicits._
import fs2.Stream
import cats.effect.{IO, Sync}
import com.deciphernow.greymatter.data.nifi.processors.utils.ErrorHandling
import org.http4s.Status.Successful

trait GmDataClient[F[_]] extends Http4sClientDsl[F] with ErrorHandling {

  implicit val securityDecoder = Decoder[Security].either(Decoder[Map[String, String]]).map(_.left.toOption)

  def defaultHandleResponseFunction[X](request: F[Request[F]])(implicit decoder: Decoder[X], F: Sync[F]): PartialFunction[Response[F], F[X]] = {
    case Successful(resp) => resp.as[String].map { response =>
      decode[X](response) match {
        case Right(some) => some
        case Left(err) => throw new Throwable(s"There was a problem decoding $response: $err")
      }
    }
    case errResponse => request.flatMap(gmDataError(errResponse, _))
  }

  def getRawResponse(request: F[Request[F]])(implicit F: Sync[F]): PartialFunction[Response[F], F[GmDataResponse[String]]] = {
    case Successful(resp) => resp.as[String].map(GmDataResponse[String](_, resp.status.code))
    case errResponse => request.flatMap(req => errResponse.as[String].map(err => GmDataResponse(s"There was an error response from ${req.uri}: $err", errResponse.status.code)))
  }

  def writeToGmData[X](client: Client[F], headers: Headers, existingRequest: F[Request[F]], handleResponseFunction: F[Request[F]] => PartialFunction[Response[F], F[X]])(implicit decoder: Decoder[X], F: Sync[F]): F[X] = {
    val request = existingRequest.map(_.withHeaders(headers))
    client.fetch(request)(handleResponseFunction(request))
  }

  def streamFromGMData[X](client: Client[F], headers: Headers, existingRequest: F[Request[F]])(implicit F: Sync[F], d: Decoder[X]): Stream[F, X] = for {
    request <- Stream.eval(existingRequest.map(_.withHeaders(headers)))
    stream <- client.stream(request).flatMap {
      case Successful(resp: Response[F]) => resp.body.through(byteArrayParser).through(decoder[F, X])
      case errResponse => Stream.eval(gmDataError[X](errResponse, request))
    }
  } yield stream

  def gmDataError[X](response: Response[F], request: Request[F])(implicit d: Decoder[X], F: Sync[F]): F[X] = response.as[String].map(err => throw new Throwable(s"There was an error response from ${request.uri} with response code ${response.status.code}: $err"))

  private def get[X](path: Uri)(client: Client[F], headers: Headers)(implicit F: Sync[F], decoder: Decoder[X]): F[X] = writeToGmData[X](client, headers, Method.GET(path), defaultHandleResponseFunction)

  protected def getSelf(rootUrl: Uri, client: Client[F], headers: Headers)(implicit F: Sync[F]): F[SelfResponse] = get(rootUrl / "self")(client: Client[F], headers: Headers)

  protected def getConfig(rootUrl: Uri, client: Client[F], headers: Headers)(implicit F: Sync[F]): F[Config] = get(rootUrl / "config")(client: Client[F], headers: Headers)

  protected def getProps[X](path: Uri.Path, headers: Headers, rootUrl: Uri, client: Client[F])(implicit decoder: Decoder[X], F: Sync[F]): F[X] = get(rootUrl / "props" / path)(client: Client[F], headers: Headers)

  protected def getFolderProps(path: Uri.Path, headers: Headers)(implicit rootUrl: Uri, client: Client[F], F: Sync[F]) = getProps[Metadata](path, headers, rootUrl, client)

  protected def getPropsAndStatus(path: Uri.Path, headers: Headers, rootUrl: Uri, client: Client[F])(implicit F: Sync[F]): F[GmDataResponse[String]] = writeToGmData(client, headers, Method.GET(parseUrl(s"$rootUrl/props/$path")), getRawResponse)

  private def getList[X](path: Uri.Path, headers: Headers)(implicit rootUrl: Uri, client: Client[F], decoder: Decoder[X], F: Sync[F]): F[List[X]] = get(parseUrl(s"$rootUrl/list/$path"))(client: Client[F], headers: Headers)

  protected def getFileList(path: Uri.Path, headers: Headers)(implicit rootUrl: Uri, client: Client[F], F: Sync[F]) = getList[Metadata](path, headers)

  protected def streamFileList(path: Uri.Path, headers: Headers)(implicit rootUrl: Uri, client: Client[F], F: Sync[F]) = streamFromGMData[Metadata](client, headers, Method.GET(parseUrl(s"$rootUrl/list/$path")))

  protected def writeFolder(metadata: List[Metadata], rootUrl: Uri, headers: Headers)(implicit client: Client[F], F: Sync[F]) = {
    val printer = Printer.spaces2.copy(dropNullValues = true)
    val body = metadata.asJson.pretty(printer)
    val multipart = Multipart[F](Vector(Part.formData("meta", body)))
    val request = Method.POST(multipart, rootUrl / "write")
    writeToGmData[List[Metadata]](client, multipart.headers ++ headers, request, defaultHandleResponseFunction).map(_.head)
  }

  def getUserFolder(rootUrl: Uri, headers: Headers, config: Config, client: Client[F])(implicit F: Sync[F]) =
    getSelf(rootUrl, client, headers).attempt.map(_.flatMap(_.getUserField(config.GMDATA_NAMESPACE_USERFIELD))).map(handleErrorAndContinue("There was an error hitting the /self endpoint of GM Data"))

  private def parseUrl(string: String) = Uri.fromString(string) match {
    case Right(url) => url
    case Left(err) => throw new Throwable(s"$string is an invalid URL: $err")
  }
}

case class Config(GMDATA_NAMESPACE_OID: String, GMDATA_NAMESPACE_USERFIELD: String)

case class SelfResponse(values: Map[String, Option[List[String]]]) {
  val getUserField = (userField: String) => values.mapValues(_.map(_.head))(userField).toRight(new Throwable(s"/self did not return a value for $userField"))
}

case class GmDataResponse[X](response: X, statusCode: Int)
