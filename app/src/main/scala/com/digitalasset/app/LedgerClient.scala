// Copyright (c) 2019, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.app

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

import com.daml.ledger.rxjava.components.{Bot, LedgerViewFlowable}
import com.daml.ledger.rxjava.DamlLedgerClient
import com.daml.ledger.rxjava.components.helpers.CommandsAndPendingSet
import com.daml.ledger.javaapi.data.{Command, Event, FiltersByParty, Identifier, LedgerOffset, Record, Transaction}
import com.daml.daml_lf_dev.DamlLf
import com.daml.daml_lf_dev.DamlLf1
import com.daml.daml_lf_dev.DamlLf1.{DottedName, FieldWithType}
import com.daml.daml_lf_dev.DamlLf1.FieldWithType.FieldCase.FIELD_INTERNED_STR
import com.daml.daml_lf_dev.DamlLf1.KeyExpr.RecordField.FieldCase
import com.daml.ledger.api.v1.PackageServiceOuterClass.{GetPackageRequest, ListPackagesRequest}
import com.google.protobuf.{CodedInputStream, Timestamp}
import io.grpc.ManagedChannelBuilder
import com.daml.ledger.api.v1.PackageServiceGrpc
import io.reactivex.Flowable

import scala.collection.JavaConverters._
import com.daml.ledger.api.v1.testing.TimeServiceGrpc
import com.daml.ledger.api.v1.testing.TimeServiceOuterClass.{GetTimeRequest, SetTimeRequest}
import com.digitalasset.app.utils.PackageUtils

case class Config
  (
    appId: String,
    hostIp: String,
    hostPort: Integer,
    maxRecordOffset: Integer,
    useStaticTime: Boolean
  )

class LedgerClient(config: Config) {
  private val client = DamlLedgerClient.newBuilder(config.hostIp, config.hostPort).build()
  client.connect()
  private val templateName2id = loadTemplates()

  // Time client
  private val channel = ManagedChannelBuilder.forAddress(config.hostIp, config.hostPort).usePlaintext.build
  private val timeClient = if (config.useStaticTime) TimeServiceGrpc.newBlockingStub(channel) else null
  private val ledgerId = client.getLedgerId

  val maxRecordOffset: Integer = config.maxRecordOffset
  val appId: String = config.appId

  // Get template id by name
  def getTemplateId(name: String) = templateName2id(name)

  // Get current ledger time
  def getTime(): Instant = {
    if(config.useStaticTime) {
      val getRequest = GetTimeRequest.newBuilder()
        .setLedgerId(ledgerId)
        .build()
      val time = timeClient.getTime(getRequest).next.getCurrentTime
      Instant.ofEpochSecond(time.getSeconds, time.getNanos)
    } else {
      Instant.now()
    }
  }

  // Set current ledger time
  def setTime(newTime: Instant): Unit = {
    if(config.useStaticTime) {
      val currentTime = getTime()
      if (currentTime.isBefore(newTime)) {
        val currentTimestamp = Timestamp.newBuilder().setSeconds(currentTime.getEpochSecond).setNanos(currentTime.getNano).build
        val newTimestamp = Timestamp.newBuilder().setSeconds(newTime.getEpochSecond).setNanos(newTime.getNano).build

        val setRequest = SetTimeRequest.newBuilder()
          .setLedgerId(ledgerId)
          .setCurrentTime(currentTimestamp)
          .setNewTime(newTimestamp)
          .build()

        timeClient.setTime(setRequest);
        ()
      }
    } else
      throw new UnsupportedOperationException("can only set time if static time is used.")
  }

  // Send a list of commands
  def sendCommands(party: String, commands: List[Command]): Unit = {
    client.getCommandClient.submitAndWait(
      UUID.randomUUID().toString,
      config.appId,
      UUID.randomUUID().toString,
      party,
      commands.asJava
    )
    ()
  }

  // Send a list of commands and wait for transaction
  def sendCommandsAndWaitForTransaction(party: String, commands: List[Command]): Transaction = {
    client.getCommandClient.submitAndWaitForTransaction(
      UUID.randomUUID().toString,
      config.appId,
      UUID.randomUUID().toString,
      party,
      commands.asJava
    ).blockingGet()
  }

  // Wire new bot
  def wireBot(transactionFilter: FiltersByParty, run: LedgerViewFlowable.LedgerView[Record] => Flowable[CommandsAndPendingSet]): Unit = {
    def runImpl(ledgerView: LedgerViewFlowable.LedgerView[Record]): Flowable[CommandsAndPendingSet] = {
      run(ledgerView)
    }

    Bot.wire(config.appId, client, transactionFilter, x => runImpl(x), x => x.getCreateArguments)
  }

  def buildInMemoryDataStore(transactionFilter: FiltersByParty, update: Event => Unit) = {
    val acsOffset = new AtomicReference[LedgerOffset](LedgerOffset.LedgerBegin.getInstance)

    client.getActiveContractSetClient.getActiveContracts(transactionFilter, true)
      .blockingForEach{response =>
        response.getOffset.ifPresent(offset => acsOffset.set(new LedgerOffset.Absolute(offset)))
        response.getCreatedEvents.forEach(e => update(e))
      }

    client.getTransactionsClient.getTransactions(acsOffset.get, transactionFilter, true).forEach{ t =>
      t.getEvents.forEach(e => update(e))
    }
  }

  // Load all existing templates
  private def loadTemplates(): Map[String, Identifier] = {
    val packages = getPackages()

    // Get all templates
    packages.flatMap {
      case (packageId, lfPackage) =>
        lfPackage
          .getModulesList.asScala
          .flatMap(m => {
            val dottedModuleName = PackageUtils.getModuleName(m, lfPackage)
            val moduleName = Schema.getSegmentName(dottedModuleName)
            m.getTemplatesList.asScala.map(t => {
              val dottedTemplateName = PackageUtils.getDefTemplateName(t, lfPackage)
              val templateName = Schema.getSegmentName(dottedTemplateName)
              (templateName, new Identifier(packageId, moduleName, templateName))
            })
          })
    }
  }

  // Load simple schema for a list of modules (required to decode jsons)
  def loadSchemas(moduleNames: List[String]): Schema.Schema = {
    val packages = getPackages().values.toList
    moduleNames.map(module => loadSchema(module, packages)).flatMap(_.toList).toMap
  }

  private def loadSchema(moduleName: String, packages: List[DamlLf1.Package]): Schema.Schema = {
    val schemas = packages.flatMap(p => Schema.buildSchema(moduleName, p))
    schemas.size match {
      case 0 => throw new IllegalArgumentException("No schema found for module " + moduleName)
      case 1 => schemas.head
      case _ => throw new NotImplementedError("Multiple schemas.")
    }
  }

  // Get all package
  private def getPackages(): Map[String, DamlLf1.Package] = {
    // Build Channel
    val cb = ManagedChannelBuilder.forAddress(config.hostIp, config.hostPort)
    cb.usePlaintext
    cb.maxInboundMessageSize(50 * 1024 * 1024)
    val channel = cb.build()

    // Create PackageService
    val packageService = PackageServiceGrpc.newBlockingStub(channel)
    val ledgerId = client.getLedgerId

    val packageIds = packageService
      .listPackages(ListPackagesRequest.newBuilder().setLedgerId(ledgerId).build)
      .getPackageIdsList
      .asByteStringList().asScala

    packageIds.map(packageId => {
      val pId = packageId.toStringUtf8
      val packageResponse =
        packageService
          .getPackage(GetPackageRequest.newBuilder().setLedgerId(ledgerId).setPackageId(pId).build)

      val cos = CodedInputStream.newInstance(packageResponse.getArchivePayload.toByteArray)
      cos.setRecursionLimit(1000)
      val payload = DamlLf.ArchivePayload.parseFrom(cos)
      (pId, payload.getDamlLf1)

  }).toMap
  }
}

object Schema {
  type Schema = Map[String, List[Field]]

  case class Field
  (
    name: String,
    cardinality: Cardinality.Cardinality,
    `type`: String,
    withMeta: Boolean,
    withReference: Boolean
  )

  object Cardinality extends Enumeration {
    type Cardinality = Value
    val OPTIONAL, ONEOF, LISTOF = Value
  }

  // Map daml lf types to a simple schema (required to decode jsons)
  def buildSchema(moduleName: String, lfPackage: DamlLf1.Package): Option[Schema] = {
    val module = lfPackage.getModulesList.asScala.toList.find(x => {
      val dottedModuleName = PackageUtils.getModuleName(x, lfPackage)
      getSegmentName(dottedModuleName) == moduleName
    })
    module.map { m =>
      m.getDataTypesList.asScala.toList.map{dataType =>
        val dataTypeName = PackageUtils.getDataTypeName(dataType, lfPackage)
        val name = getSegmentName(dataTypeName)
        val fields = dataType.getRecord.getFieldsList.asScala.toList.map(f => mapType(f, f.getType, lfPackage))
        (name, fields)
      }.toMap
    }
  }

  private def mapType(field: FieldWithType, damlLfType: DamlLf1.Type, lfPackage: DamlLf1.Package): Schema.Field = {
    val name = fieldName(field, lfPackage)
    damlLfType.getPrim.getPrim.getValueDescriptor.getName match {
      case "INT64"        => Schema.Field(name, Cardinality.ONEOF, "PrimInt64", false, false)
      case "DECIMAL"      => Schema.Field(name, Cardinality.ONEOF, "PrimDecimal", false, false)
      case "NUMERIC"      => Schema.Field(name, Cardinality.ONEOF, "PrimNumeric", false, false)
      case "TEXT"         => Schema.Field(name, Cardinality.ONEOF, "PrimText", false, false)
      case "BOOL"         => Schema.Field(name, Cardinality.ONEOF, "PrimBool", false, false)
      case "DATE"         => Schema.Field(name, Cardinality.ONEOF, "PrimDate", false, false)
      case "TIMESTAMP"    => Schema.Field(name, Cardinality.ONEOF, "PrimTimestamp", false, false)
      case "PARTY"        => Schema.Field(name, Cardinality.ONEOF, "PrimParty", false, false)
      case "CONTRACT_ID"  => Schema.Field(name, Cardinality.ONEOF, "PrimContractId", false, false)
      case "ARROW"        => Schema.Field(name, Cardinality.ONEOF, "PrimArrow", false, false)
      case "OPTIONAL"     =>
        val subType = mapType(field, damlLfType.getPrim.getArgsList.get(0), lfPackage)
        if (subType.cardinality == Cardinality.ONEOF) subType.copy(cardinality = Cardinality.OPTIONAL)
        else throw new NotImplementedError("Nested optionals or lists not supported.")
      case "LIST"         =>
        val subType = mapType(field, damlLfType.getPrim.getArgsList.get(0), lfPackage)
        if (subType.cardinality == Cardinality.ONEOF) subType.copy(cardinality = Cardinality.LISTOF)
        else throw new NotImplementedError("Nested optionals or lists not supported.")
      case "UNIT"         =>
        val typeConDottedName = PackageUtils.getTypeConName(damlLfType.getCon.getTycon, lfPackage)
        if (damlLfType.getCon.getArgsCount > 0) {
          val t = getSegmentName(typeConDottedName)
          t match {
            case "ReferenceWithMeta" =>
              val subType = mapType(field, damlLfType.getCon.getArgs(0), lfPackage)
              if (subType.cardinality == Cardinality.ONEOF) subType.copy(withReference = true)
              else throw new NotImplementedError("Nested FieldWithMeta types not supported.")
            case "BasicReferenceWithMeta" =>
              val subType = mapType(field, damlLfType.getCon.getArgs(0), lfPackage)
              if (subType.cardinality == Cardinality.ONEOF) subType.copy(withReference = true)
              else throw new NotImplementedError("Nested FieldWithMeta types not supported.")
            case "FieldWithMeta" =>
              val subType = mapType(field, damlLfType.getCon.getArgs(0), lfPackage)
              if (subType.cardinality == Cardinality.ONEOF) subType.copy(withMeta = true)
              else throw new NotImplementedError("Nested FieldWithMeta types not supported.")
            case _ => throw new NotImplementedError("Types with arguments not supported.")
          }
        }
        else Schema.Field(name, Cardinality.ONEOF, getSegmentName(typeConDottedName), false, false)
      case other          => throw new Exception("PrimType " + other + " not supported.")
    }
  }

  private def fieldName(f: DamlLf1.FieldWithType, lfPackage: DamlLf1.Package): String = {
    f.getFieldCase match {
      case FIELD_INTERNED_STR => lfPackage.getInternedStrings(f.getFieldInternedStr)
      case _ => f.getFieldStr
    }
  }

  def getSegmentName(name: DottedName): String = {
    val segments = 1 to name.getSegmentsCount map(i => name.getSegments(i-1))
    segments.mkString(".")
  }
}
