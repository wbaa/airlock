package com.ing.wbaa.gargoyle.proxy.api

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Route
import com.ing.wbaa.gargoyle.proxy.data.{AccessType, S3Request, Secret}
import com.ing.wbaa.gargoyle.proxy.handler.RequestHandlerBase
import com.ing.wbaa.gargoyle.proxy.providers.{AuthenticationProvider, AuthorizationProviderBase}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait ProxyService extends LazyLogging
  with AuthenticationProvider
  with AuthorizationProviderBase
  with RequestHandlerBase {

  implicit val system: ActorSystem

  // no validation of request currently
  // once we get comfortable with get/put/del we can add permCheck
  import akka.http.scaladsl.server.Directives._

  val proxyServiceRoute: Route =
    withoutSizeLimit {
      extractClientIP { remoteAddress =>
        Route { ctx =>

          def handleAuthentication(htr: HttpRequest): Future[Either[Secret, HttpResponse]] =
            isAuthenticated("accesskey", Some("token")).map {
              case None => Right(HttpResponse(StatusCodes.ProxyAuthenticationRequired))
              case Some(secret) => Left(secret)
            }

          def handleValidation(htr: HttpRequest, secret: Secret): Either[Unit, HttpResponse] =
            if(validateUserRequest(htr, secret)) Left(Unit)
            else Right(HttpResponse(StatusCodes.BadRequest))

          def handleAuthorization(htr: HttpRequest): Either[Unit, HttpResponse] =
            if(isAuthorized(S3Request("demobucket", AccessType.read, getUser("accesskey"), Set("testgroup")))) Left(Unit)
            else Right(HttpResponse(StatusCodes.Unauthorized))

          val requestProcessor: HttpRequest => Future[HttpResponse] = htr =>
            handleAuthentication(htr)
            .map {
              case Left(secret) => handleValidation(htr, secret)
              case Right(r) => Right(r)
            }
            .map {
              case Left(_) => handleAuthorization(htr)
              case Right(r) => Right(r)
            }
            .flatMap {
              case Left(_) => executeRequest(htr, remoteAddress)
              case Right(r) => Future(r)
            }

          requestProcessor(ctx.request).flatMap(r => ctx.complete(r))
        }
      }
    }
}
