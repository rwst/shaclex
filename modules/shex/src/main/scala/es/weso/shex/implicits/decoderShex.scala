package es.weso.shex.implicits
import io.circe._
import cats.syntax.either._
import es.weso.rdf.nodes._
import es.weso.rdf.nodes.IRI._
import util.matching._
import es.weso.shex._
import es.weso.rdf._
import es.weso.json.DecoderUtils._
import es.weso.rdf.PREFIXES.rdf_langString

object decoderShEx {

  implicit lazy val decodeSchema: Decoder[Schema] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "Schema").right
      _ <- fixedFieldValue(c, "@context", "http://www.w3.org/ns/shex.jsonld").right
      prefixes <- optFieldDecodeMap[Prefix, IRI](c, "prefixes").right
      imports <- optFieldDecode[List[IRI]](c, "imports").right
      base <- optFieldDecode[IRI](c, "base").right
      startActs <- optFieldDecode[List[SemAct]](c, "startActs").right
      start <- optFieldDecode[ShapeExpr](c, "start").right
      shapes <- optFieldDecode[List[ShapeExpr]](c, "shapes").right
      // TODO: Check how to represent tripleExprMap
    } yield Schema(prefixes.map(PrefixMap(_)), base, startActs, start, shapes, None, imports.getOrElse(List()))
  }

  implicit lazy val decodePrefix: Decoder[Prefix] =
    Decoder[String].map(Prefix(_))

  implicit lazy val keyDecoderPrefix: KeyDecoder[Prefix] =
    KeyDecoder.instance { str => parsePrefix(str).toOption }

  implicit lazy val decodeShapeLabel: Decoder[ShapeLabel] =
    Decoder[String].emap(str => parseShapeLabel(str))

  /*  implicit lazy val keyDecoderShapeLabel: KeyDecoder[ShapeLabel] =
    KeyDecoder.instance { str => parseShapeLabel(str).toOption } */

  implicit lazy val decodeBNodeId: Decoder[BNodeId] =
    Decoder[String].map(BNodeId(_))

  implicit lazy val decodeIRI: Decoder[IRI] =
    Decoder[String].emap(parseIRI(_))

  implicit lazy val decodeSemAct: Decoder[SemAct] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "SemAct").right
      name <- fieldDecode[IRI](c, "name").right
      code <- optFieldDecode[String](c, "code").right
    } yield SemAct(name, code)
  }

  implicit lazy val decodeMax: Decoder[Max] =
    Decoder[Int].map(n => IntMax(n)).or(
      Decoder[String].map(s => Star))

  implicit lazy val decodeShapeExpr: Decoder[ShapeExpr] =
    decoderShapeRef.or(
      decoderLabeledShapeExpr)

  lazy val decoderShapeRef: Decoder[ShapeRef] =
    Decoder[ShapeLabel].map(lbl => ShapeRef(lbl))

  lazy val decoderLabeledShapeExpr: Decoder[ShapeExpr] = Decoder.instance { c =>
    c.downField("type").as[String] match {
      case Right("ShapeOr") => c.as[ShapeOr]
      case Right("ShapeAnd") => c.as[ShapeAnd]
      case Right("ShapeNot") => c.as[ShapeNot]
      case Right("NodeConstraint") => c.as[NodeConstraint]
      case Right("Shape") => c.as[Shape]
      case Right("ShapeExternal") => c.as[ShapeExternal]
      case other =>
        Either.left(DecodingFailure(s"Decoding ShapeExpr. Unexpected value $other", Nil))
    }
  }

  implicit lazy val decodeShapeOr: Decoder[ShapeOr] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "ShapeOr").right
      id <- optFieldDecode[ShapeLabel](c, "id").right
      ses <- fieldDecode[List[ShapeExpr]](c, "shapeExprs").right
    } yield ShapeOr(id, ses)
  }

  implicit lazy val decodeShapeAnd: Decoder[ShapeAnd] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "ShapeAnd").right
      id <- optFieldDecode[ShapeLabel](c, "id").right
      ses <- fieldDecode[List[ShapeExpr]](c, "shapeExprs").right
    } yield ShapeAnd(id, ses)
  }

  implicit lazy val decodeShapeNot: Decoder[ShapeNot] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "ShapeNot").right
      id <- optFieldDecode[ShapeLabel](c, "id").right
      se <- fieldDecode[ShapeExpr](c, "shapeExpr").right
    } yield ShapeNot(id, se)
  }

  implicit lazy val decodeNodeConstraint: Decoder[NodeConstraint] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "NodeConstraint").right
      id <- optFieldDecode[ShapeLabel](c, "id").right
      nodeKind <- optFieldDecode[NodeKind](c, "nodeKind").right
      datatype <- optFieldDecode[IRI](c, "datatype").right
      values <- optFieldDecode[List[ValueSetValue]](c, "values").right
      xsFacets <- getXsFacets(c).right
    } yield NodeConstraint(id, nodeKind, datatype, xsFacets, values)
  }

  def getXsFacets(c: HCursor): Decoder.Result[List[XsFacet]] = {
    val keys: Vector[String] = c.keys.map(_.toVector).getOrElse(Vector[String]())
    val rs: List[Either[DecodingFailure, Option[XsFacet]]] =
      keys.toList.map(extractXsFacet(_, c))
    sequenceEither(rs)
  }


  def extractXsFacet(name: String, c: HCursor): Either[DecodingFailure, Option[XsFacet]] = {
    name match {
      case "length" => c.get[Int](name).map(n => Some(Length(n)))
      case "minlength" => c.get[Int](name).map(n => Some(MinLength(n)))
      case "maxlength" => c.get[Int](name).map(n => Some(MaxLength(n)))
      case "pattern" => for {
        p <- fieldDecode[String](c,name)
        flags <- optFieldDecode[String](c,"flags")
      } yield Some(Pattern(p,flags))
      case "mininclusive" => c.get[NumericLiteral](name).map(p => Some(MinInclusive(p)))
      case "minexclusive" => c.get[NumericLiteral](name).map(p => Some(MinExclusive(p)))
      case "maxinclusive" => c.get[NumericLiteral](name).map(p => Some(MaxInclusive(p)))
      case "maxexclusive" => c.get[NumericLiteral](name).map(p => Some(MaxExclusive(p)))
      case "fractiondigits" => c.get[Int](name).map(p => Some(FractionDigits(p)))
      case "totaldigits" => c.get[Int](name).map(p => Some(TotalDigits(p)))
      case _ => Right(None)
    }
  }


  implicit lazy val decodeNumericLiteral: Decoder[NumericLiteral] =
    Decoder[Int].map(n => NumericInt(n)).or(
      Decoder[Double].map(n => NumericDouble(n)).or(
        Decoder[BigDecimal].map(n => NumericDecimal(n))))

  implicit lazy val decodeNodeKind: Decoder[NodeKind] = Decoder.instance { c =>
    c.field("nodeKind").as[String].flatMap {
      case "iri" => Right(IRIKind)
      case "bnode" => Right(BNodeKind)
      case "nonliteral" => Right(NonLiteralKind)
      case "literal" => Right(LiteralKind)
      case other =>
        Left(DecodingFailure(s"Decoding nodeKind. Unexpected value $other", Nil))
    }
  }

  implicit lazy val decodeShape: Decoder[Shape] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "Shape")
      id <- optFieldDecode[ShapeLabel](c, "id").right
      virtual <- optFieldDecode[Boolean](c, "virtual")
      closed <- optFieldDecode[Boolean](c, "closed")
      extra <- optFieldDecode[List[IRI]](c, "extra")
      expression <- optFieldDecode[TripleExpr](c, "expression")
      inherit <- optFieldDecode[ShapeLabel](c, "inherit")
      semActs <- optFieldDecode[List[SemAct]](c, "semActs")
      annotations <- optFieldDecode[List[Annotation]](c,"annotations")
    } yield Shape(id, virtual, closed, extra, expression, inherit, semActs, annotations)
  }

  implicit lazy val decodeTripleExpr: Decoder[TripleExpr] =
    decoderInclusion.or(decoderLabeledTripleExpr)

  lazy val decoderInclusion: Decoder[Inclusion] =
    Decoder[ShapeLabel].map(lbl => Inclusion(lbl))

  lazy val decoderLabeledTripleExpr: Decoder[TripleExpr] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "EachOf" => c.as[EachOf]
      case "OneOf" => c.as[OneOf]
      //      case "Inclusion"        => c.as[Inclusion]
      case "TripleConstraint" => c.as[TripleConstraint]
      case other =>
        Either.left(DecodingFailure(s"Decoding TripleExpr. Unexpected value $other", Nil))
    }
  }

  implicit lazy val decodeEachOf: Decoder[EachOf] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "EachOf")
      id <- optFieldDecode[ShapeLabel](c, "id").right
      expressions <- fieldDecode[List[TripleExpr]](c, "expressions")
      min <- optFieldDecode[Int](c, "min")
      max <- optFieldDecode[Max](c, "max")
      semActs <- optFieldDecode[List[SemAct]](c, "semActs")
      annotations <- optFieldDecode[List[Annotation]](c, "annotations")
    } yield EachOf(id, expressions, min, max, semActs, annotations)
  }

  implicit lazy val decodeOneOf: Decoder[OneOf] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "OneOf")
      id <- optFieldDecode[ShapeLabel](c, "id").right
      expressions <- fieldDecode[List[TripleExpr]](c, "expressions")
      min <- optFieldDecode[Int](c, "min")
      max <- optFieldDecode[Max](c, "max")
      semActs <- optFieldDecode[List[SemAct]](c, "semActs")
      annotations <- optFieldDecode[List[Annotation]](c, "annotations")
    } yield OneOf(id, expressions, min, max, semActs, annotations)
  }

  implicit lazy val decodeAnnotation: Decoder[Annotation] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "Annotation")
      predicate <- fieldDecode[IRI](c, "predicate")
      obj <- fieldDecode[ObjectValue](c, "object")
    } yield Annotation(predicate, obj)
  }

  /*  implicit lazy val decodeInclusion: Decoder[Inclusion] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "Inclusion")
      include <- fieldDecode[ShapeLabel](c, "include")
    } yield Inclusion(include)
  } */

  /*  implicit lazy val decodeShapeRef: Decoder[ShapeRef] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "ShapeRef")
      reference <- fieldDecode[ShapeLabel](c, "reference")
    } yield ShapeRef(reference)
  } */

  implicit lazy val decodeShapeExternal: Decoder[ShapeExternal] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "ShapeExternal")
      id <- optFieldDecode[ShapeLabel](c, "id").right
    } yield ShapeExternal(id)
  }

  implicit lazy val decodeValueSetValue: Decoder[ValueSetValue] =
   Decoder[Language].map(identity).or(
    Decoder[IRIStem].map(identity).or(
      Decoder[IRIStemRange].map(identity).or(
        Decoder[LanguageStem].map(identity).or(
          Decoder[LanguageStemRange].map(identity).or(
            Decoder[LiteralStem].map(identity).or(
              Decoder[LiteralStemRange].map(identity).or(
                Decoder[ObjectValue].map(identity)
   )))))))

  implicit lazy val decodeObjectValue: Decoder[ObjectValue] =
    Decoder[IRI].map(IRIValue(_)).or(
      decodeObjectLiteral.map(identity)
  )
  implicit lazy val decodeLanguage: Decoder[Language] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "Language")
      lang <- fieldDecode[String](c, "languageTag")
    } yield Language(lang)
  }

  implicit lazy val decodeObjectLiteral: Decoder[ObjectLiteral] = Decoder.instance { c =>
    for {
      value <- fieldDecode[String](c, "value").right
      optLang <- optFieldDecode[String](c, "language").right
      optType <- optFieldDecode[IRI](c, "type").right
    } yield (optLang, optType) match {
      case (None, None) => StringValue(value)
      case (Some(lang), None) => LangString(value, lang)
      case (None, Some(iri)) => DatatypeString(value, iri)
      case (Some(lang), Some(iri)) => if (iri == rdf_langString) {
        LangString(value, lang)
      } else {
        throw new Exception(s"Unsupported language tagged literal $value^^$lang with datatype $iri != $rdf_langString")
      }
    }
  }

  def parseObjectValue(s: String): Either[String, ObjectValue] = {
    s match {
      case langRegex(s, l) => Right(LangString(s, l))
      case datatypeRegex(s, d) => parseIRI(d).map(i => DatatypeString(s, i))
      case stringRegex(str) => Right(StringValue(str))
      case iriRegex(str) => parseIRI(str).map(i => IRIValue(i))
      case _ => Left(s"$s doesn't match valueset value")
    }
  }

  implicit lazy val decodeIRIValue: Decoder[IRIValue] =
    Decoder[String].emap(s => parseIRI(s)).map(i => IRIValue(i))

  implicit lazy val decodeIRIStem: Decoder[IRIStem] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "IriStem")
      stem <- fieldDecode[IRI](c, "stem")
    } yield IRIStem(stem)
  }

  implicit lazy val decodeIRIStemRange: Decoder[IRIStemRange] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "IriStemRange")
      stem <- fieldDecode[IRIStemRangeValue](c, "stem")
      exclusions <- optFieldDecode[List[ValueSetValue]](c, "exclusions")
    } yield IRIStemRange(stem, exclusions)
  }

  implicit lazy val decodeLanguageStem: Decoder[LanguageStem] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "LanguageStem")
      stem <- fieldDecode[String](c, "stem")
    } yield LanguageStem(stem)
  }

  implicit lazy val decodeLanguageStemRange: Decoder[LanguageStemRange] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "LanguageStemRange")
      stem <- fieldDecode[LanguageStemRangeValue](c, "stem")
      exclusions <- optFieldDecode[List[ValueSetValue]](c, "exclusions")
    } yield LanguageStemRange(stem, exclusions)
  }

  implicit lazy val decodeLiteralStem: Decoder[LiteralStem] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "LiteralStem")
      stem <- fieldDecode[String](c, "stem")
    } yield LiteralStem(StringValue(stem))
  }

  implicit lazy val decodeLiteralStemRange: Decoder[LiteralStemRange] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "LiteralStemRange")
      stem <- fieldDecode[LiteralStemRangeValue](c, "stem")
      exclusions <- optFieldDecode[List[ValueSetValue]](c, "exclusions")
    } yield LiteralStemRange(stem, exclusions)
  }

  implicit lazy val decodeIRIStemRangeValue: Decoder[IRIStemRangeValue] =
    Decoder[IRI].map(iri => IRIStemValueIRI(iri)).or(
      Decoder[IRIStemWildcard].map(w => w))

  implicit lazy val decodeIRIStemWildcard: Decoder[IRIStemWildcard] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "Wildcard")
    } yield IRIStemWildcard()
  }

  implicit lazy val decodeLanguageStemRangeValue: Decoder[LanguageStemRangeValue] =
    Decoder[String].map(stem => LanguageStemRangeLang(stem)).or(
      Decoder[LanguageStemRangeWildcard].map(identity)
    )

  implicit lazy val decodeLanguageRangeStemWildcard: Decoder[LanguageStemRangeWildcard] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "Wildcard")
    } yield LanguageStemRangeWildcard()
  }

  implicit lazy val decodeLiteralStemRangeValue: Decoder[LiteralStemRangeValue] =
    Decoder[String].map(str => LiteralStemRangeValueObject(StringValue(str))).or(
    Decoder[ObjectLiteral].map(obj => LiteralStemRangeValueObject(obj)).or(
      Decoder[LiteralStemRangeWildcard].map(identity)
    )
    )

  implicit lazy val decodeLiteralStemRangeWildcard: Decoder[LiteralStemRangeWildcard] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "Wildcard")
    } yield LiteralStemRangeWildcard()
  }

  implicit lazy val decodeTripleConstraint: Decoder[TripleConstraint] = Decoder.instance { c =>
    for {
      _ <- fixedFieldValue(c, "type", "TripleConstraint")
      id <- optFieldDecode[ShapeLabel](c, "id").right
      inverse <- optFieldDecode[Boolean](c, "inverse")
      negated <- optFieldDecode[Boolean](c, "negated")
      predicate <- fieldDecode[IRI](c, "predicate")
      valueExpr <- optFieldDecode[ShapeExpr](c, "valueExpr")
      min <- optFieldDecode[Int](c, "min")
      max <- optFieldDecode[Max](c, "max")
      semActs <- optFieldDecode[List[SemAct]](c, "semActs")
      annotations <- optFieldDecode[List[Annotation]](c, "annotations")
    } yield TripleConstraint(id, inverse, negated, predicate, valueExpr, min, max, semActs, annotations)
  }

  def parsePrefix(str: String): Either[String, Prefix] =
    str match {
      case prefixRegex(p) => Either.right(Prefix(p))
      case _ => Either.left(s"$str doesn't match prefix regex $prefixRegex")
    }

  def parseShapeLabel(str: String): Either[String, ShapeLabel] = {
    str match {
      // Be careful with the order...
      case bNodeRegex(bNodeId) => Either.right(BNodeLabel(BNodeId(bNodeId)))
      case iriRegex(i) => parseIRI(i).map(iri => IRILabel(iri))
      case _ => Either.left(s"$str doesn't match IRI or BNode")
    }
  }

  def parseLang(str: String): Either[String, LangString] =
    str match {
      case langRegex(s, l) => Either.right(LangString(s, l))
      case _ => Either.left(s"$str doesn't match IRI regex $iriRegex")
    }

  lazy val prefixRegex: Regex = "^(.*)$".r // PN_PREFIX_STR.r
  lazy val bNodeRegex: Regex = "^_:(.*)$".r
  lazy val stringRegex: Regex = "^\"(.*)\"$".r
  lazy val langRegex: Regex = "^\"(.*)\"@(.*)$".r
  lazy val datatypeRegex: Regex = "^\"(.[^\"]*)\"\\^\\^(.*)$".r

  /*
def startMiddleAltRep_Str(start: String, middleAlt: String, repEnd: String): String = {
    "(" + start + "(((" + repEnd + "|" + middleAlt + ")*(" + repEnd + "))?)" + ")"
}

lazy val PN_PREFIX_STR = startMiddleAltRep_Str(PN_CHARS_BASE, "\\.", PN_CHARS_STR)
lazy val PN_CHARS_STR = PN_CHARS_U + """|\-|[0-9]|\u00B7|[\u0300-\u036F]|[\u203F-\u2040]"""
lazy val PN_CHARS_U = PN_CHARS_BASE + "|_"
lazy val PN_CHARS_BASE =
    """[a-zA-Z\u00C0-\u00D6\u00D8-\u00F6""" +
      """\u00F8-\u02FF\u0370-\u037D\u037F-\u1FFF""" +
      """\u200C-\u200D\u2070-\u218F\u2C00-\u2FEF""" +
      """\u3001-\uD7FF\uF900-\uFDCF\uFDF0-\uFFFD""" +
      """\x{10000}-\x{EFFFF}]"""
*/

  // Todo: Use "sequence" when I find why it gives a type error...
  def sequenceEither[E, A](xs: List[Either[E, Option[A]]]): Either[E, List[A]] = {
    val zero: Either[E, List[A]] = Either.right(List())
    def next(r: Either[E, List[A]], x: Either[E, Option[A]]): Either[E, List[A]] =
      x match {
        case Left(e) => Left(e)
        case Right(None) => r
        case Right(Some(v)) => r match {
          case Left(e) => Left(e)
          case Right(vs) => Right(v :: vs)
        }
      }
    xs.foldLeft(zero)(next)
  }

  def mapEither[A, B, C](v: Either[A, B], f: B => C): Either[A, C] = {
    v match {
      case Left(e) => Left(e)
      case Right(x) => Right(f(x))
    }
  }

}
