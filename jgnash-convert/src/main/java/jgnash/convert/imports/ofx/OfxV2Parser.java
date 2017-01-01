/*
 * jGnash, a personal finance application
 * Copyright (C) 2001-2017 Craig Cavanaugh
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package jgnash.convert.imports.ofx;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import jgnash.convert.common.OfxTags;
import jgnash.convert.imports.ImportSecurity;
import jgnash.engine.TransactionType;
import jgnash.util.FileMagic;
import jgnash.util.ResourceUtils;

/**
 * StAX based parser for 2.x OFX (XML) files.
 *
 * This parser will intentionally absorb higher level elements and drop through to simplify and reduce code.
 *
 * @author Craig Cavanaugh
 */
public class OfxV2Parser implements OfxTags {

    private static final Logger logger = Logger.getLogger("OfxV2Parser");

    private static final String EXTRA_SPACE_REGEX = "\\s+";

    private static final String ENCODING = StandardCharsets.UTF_8.name();

    /**
     * Default language is assumed to be English unless the import file defines it
     */
    private String language = "ENG";

    private int statusCode;

    private String statusSeverity;

    private OfxBank bank;

    static void enableDetailedLogFile() {
        try {
            final Handler fh = new FileHandler("%h/jgnash-ofx.log", false);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);
            logger.setLevel(Level.ALL);
        } catch (final IOException ioe) {
            logger.severe(ResourceUtils.getString("Message.Error.LogFileHandler"));
        }
    }

    public static OfxBank parse(final File file) throws Exception {

        final OfxV2Parser parser = new OfxV2Parser();

        if (FileMagic.isOfxV1(file)) {
            logger.info("Parsing OFX Version 1 file");
            parser.parse(OfxV1ToV2.convertToXML(file), FileMagic.getOfxV1Encoding(file));
        } else if (FileMagic.isOfxV2(file)) {
            logger.info("Parsing OFX Version 2 file");
            parser.parseFile(file);
        } else {
            logger.info("Unknown OFX Version");
        }

        if (parser.getBank() == null) {
            throw new Exception("Bank import failed");
        }

        return parser.getBank();
    }

    /**
     * Parse a date. Time zone and seconds are ignored
     * <p>
     * YYYYMMDDHHMMSS.XXX [gmt offset:tz name]
     *
     * @param date String form of the date
     * @return parsed date
     */
    @SuppressWarnings("MagicConstant")
    private static LocalDate parseDate(final String date) {
        int year = Integer.parseInt(date.substring(0, 4)); // year
        int month = Integer.parseInt(date.substring(4, 6)); // month
        int day = Integer.parseInt(date.substring(6, 8)); // day

        return LocalDate.of(year, month, day);
    }

    private static BigDecimal parseAmount(final String amount) {

        /* Must trim the amount for a clean parse
         * Some banks leave extra spaces before the value
         */

        try {
            return new BigDecimal(amount.trim());
        } catch (final NumberFormatException e) {
            if (amount.contains(",")) { // OFX file in not valid and uses commas for decimal separators

                // Use the French locale as it uses commas for decimal separators
                DecimalFormat df = (DecimalFormat) NumberFormat.getInstance(Locale.FRENCH);
                df.setParseBigDecimal(true);    // force return value of BigDecimal

                try {
                    return (BigDecimal) df.parseObject(amount.trim());
                } catch (final ParseException pe) {
                    logger.log(Level.INFO, "Parse amount was: {0}", amount);
                    logger.log(Level.SEVERE, e.getLocalizedMessage(), pe);
                }
            }

            return BigDecimal.ZERO; // give up at this point
        }
    }

    /**
     * Parses an InputStream and assumes UTF-8 encoding
     *
     * @param stream InputStream to parse
     */
    public void parse(final InputStream stream) {
        parse(stream, ENCODING);
    }

    /**
     * Parses an InputStream using a specified encoding
     *
     * @param stream   InputStream to parse
     * @param encoding encoding to use
     */
    private void parse(final InputStream stream, final String encoding) {
        logger.entering(OfxV2Parser.class.getName(), "parse");

        bank = new OfxBank();

        final XMLInputFactory inputFactory = XMLInputFactory.newInstance();

        try (InputStream input = new BufferedInputStream(stream)) {
            XMLStreamReader reader = inputFactory.createXMLStreamReader(input, encoding);
            readOfx(reader);
        } catch (IOException | XMLStreamException e) {
            logger.log(Level.SEVERE, e.toString(), e);
        }

        logger.exiting(OfxV2Parser.class.getName(), "parse");
    }

    private void parseFile(final File file) {

        try (InputStream stream = new FileInputStream(file)) {
            parse(stream);
        } catch (final IOException e) {
            logger.log(Level.SEVERE, e.toString(), e);
        }
    }

    public void parse(final String string, final String encoding) throws UnsupportedEncodingException {
        parse(new ByteArrayInputStream(string.getBytes(encoding)), encoding);
    }

    private void readOfx(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "readOfx");

        while (reader.hasNext()) {

            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case OFX:   // consume the OFX header here
                            break;
                        case SIGNONMSGSRSV1:
                            parseSignOnMessageSet(reader);
                            break;
                        case BANKMSGSRSV1:
                            parseBankMessageSet(reader);
                            break;
                        case CREDITCARDMSGSRSV1:
                            parseCreditCardMessageSet(reader);
                            break;
                        case INVSTMTMSGSRSV1:
                            parseInvestmentAccountMessageSet(reader);
                            break;
                        case SECLISTMSGSRSV1:
                            parseSecuritesMessageSet(reader);
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown message set {0}", reader.getLocalName());
                            break;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "readOfx");
    }

    private void parseInvestmentAccountMessageSet(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseInvestmentAccountMessageSet");

        final QName parsingElement = reader.getName();

        parse:
        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case STATUS:
                            parseStatementStatus(reader);
                            break;
                        case CURDEF:
                            bank.currency = reader.getElementText();
                            break;
                        case INVACCTFROM:
                            parseAccountInfo(reader);
                            break;
                        case INVTRANLIST:
                            parseInvestmentTransactionList(reader);
                            break;
                        case INVSTMTRS:     // consume the statement download
                            break;
                        case INVPOSLIST:    // consume the securites position list; TODO: Use for reconcile
                        case INVBAL:        // consume the investment account balance; TODO: Use for reconcile
                        case INVOOLIST:     // consume open orders
                            consumeElement(reader);
                            break;
                        case INVSTMTTRNRS:
                        case TRNUID:
                        case DTASOF:    // statement date
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown INVSTMTMSGSRSV1 element: {0}", reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the investment account message set aggregate");
                        break parse;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "parseInvestmentAccountMessageSet");
    }

    /**
     * Parses a BANKMSGSRSV1 element
     *
     * @param reader shared XMLStreamReader
     * @throws XMLStreamException XML parsing error has occurred
     */
    private void parseBankMessageSet(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseBankMessageSet");

        final QName parsingElement = reader.getName();

        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case STATUS:
                            parseStatementStatus(reader);
                            break;
                        case CURDEF:
                            bank.currency = reader.getElementText();
                            break;
                        case LEDGERBAL:
                            parseLedgerBalance(reader);
                            break;
                        case AVAILBAL:
                            parseAvailableBalance(reader);
                            break;
                        case BANKACCTFROM:
                            parseAccountInfo(reader);
                            break;
                        case BANKTRANLIST:
                            parseBankTransactionList(reader);
                            break;
                        case STMTTRNRS: // consume it
                        case TRNUID:
                        case STMTRS:
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown BANKMSGSRSV1 element: {0}", reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the bank message set aggregate");
                        break;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "parseBankMessageSet");
    }

    /**
     * Parses a CREDITCARDMSGSRSV1 element
     *
     * @param reader shared XMLStreamReader
     * @throws XMLStreamException XML parsing error has occurred
     */
    private void parseCreditCardMessageSet(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseCreditCardMessageSet");

        final QName parsingElement = reader.getName();

        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case STATUS:
                            parseStatementStatus(reader);
                            break;
                        case CURDEF:
                            bank.currency = reader.getElementText();
                            break;
                        case LEDGERBAL:
                            parseLedgerBalance(reader);
                            break;
                        case AVAILBAL:
                            parseAvailableBalance(reader);
                            break;
                        case CCACCTFROM:
                            parseAccountInfo(reader);
                            break;
                        case BANKTRANLIST:
                            parseBankTransactionList(reader);
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown CREDITCARDMSGSRSV1 element: {0}", reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the credit card message set aggregate");
                        break;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "parseCreditCardMessageSet");
    }

    /**
     * Parses a SECLISTMSGSRSV1 element
     *
     * @param reader shared XMLStreamReader
     * @throws XMLStreamException XML parsing error has occurred
     */
    private void parseSecuritesMessageSet(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseSecuritesMessageSet");

        final QName parsingElement = reader.getName();

        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case SECLIST:
                            parseSecuritiesList(reader);
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown SECLISTMSGSRSV1 element: {0}", reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the sercurites set");
                        break;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "parseSecuritesMessageSet");
    }

    private void parseSecuritiesList(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseSecuritiesList");

        final QName parsingElement = reader.getName();

        parse:
        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case ASSETCLASS:
                        case OPTTYPE:
                        case STRIKEPRICE:
                        case DTEXPIRE:
                        case SHPERCTRCT:
                        case YIELD:
                            break;  // just consume it, not used
                        case SECID: // underlying stock for an Option.  Not used, so consume it here
                        case UNIQUEID:
                        case UNIQUEIDTYPE:
                            break;
                        case SECINFO:
                            parseSecurity(reader);
                            break;
                        case MFINFO: // just consume it
                        case OPTINFO:
                        case STOCKINFO:
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown SECLIST element: {0}", reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the securities list");
                        break parse;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "parseSecuritiesList");
    }

    private void parseSecurity(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseSecurity");

        final QName parsingElement = reader.getName();

        final ImportSecurity importSecurity = new ImportSecurity();

        parse:
        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case FIID:
                        case SECID: // consume it:
                            break;
                        case UNIQUEIDTYPE:
                            importSecurity.idType = reader.getElementText().trim();
                            break;
                        case UNIQUEID:
                            importSecurity.setId(reader.getElementText().trim());
                            break;
                        case SECNAME:
                            importSecurity.setSecurityName(reader.getElementText().trim());
                            break;
                        case TICKER:
                            importSecurity.ticker = reader.getElementText().trim();
                            break;
                        case UNITPRICE:
                            importSecurity.setUnitPrice(parseAmount(reader.getElementText()));
                            break;
                        case DTASOF:
                            importSecurity.setLocalDate(parseDate(reader.getElementText()));
                            break;
                        case CURRENCY: // consume the currency aggregate for unit price and handle here
                        case ORIGCURRENCY:
                            break;
                        case RATING:    // consume, not used
                            break;
                        case CURSYM:
                            importSecurity.setCurrency(reader.getElementText().trim());
                            break;
                        case CURRATE:
                            importSecurity.setCurrencyRate(parseAmount(reader.getElementText()));
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown SECINFO element: {0}", reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the security info");
                        break parse;
                    }
                default:
            }
        }

        bank.addSecurity(importSecurity);

        logger.exiting(OfxV2Parser.class.getName(), "parseSecurity");
    }

    /**
     * Parses a BANKTRANLIST element
     *
     * @param reader shared XMLStreamReader
     * @throws XMLStreamException XML parsing error has occurred
     */
    private void parseBankTransactionList(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseBankTransactionList");

        final QName parsingElement = reader.getName();

        parse:
        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case DTSTART:
                            bank.dateStart = parseDate(reader.getElementText());
                            break;
                        case DTEND:
                            bank.dateEnd = parseDate(reader.getElementText());
                            break;
                        case STMTTRN:
                            parseBankTransaction(reader);
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown BANKTRANLIST element: {0}", reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the bank transaction list");
                        break parse;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "parseBankTransactionList");
    }

    private void parseInvestmentTransactionList(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseInvestmentTransactionList");

        final QName parsingElement = reader.getName();

        parse:
        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case DTSTART:
                            bank.dateStart = parseDate(reader.getElementText());
                            break;
                        case DTEND:
                            bank.dateEnd = parseDate(reader.getElementText());
                            break;
                        case BUYSTOCK:
                        case BUYMF:
                        case BUYOTHER:
                        case INCOME:
                        case REINVEST:
                            parseInvestmentTransaction(reader);
                            break;
                        case INVBANKTRAN:
                            parseBankTransaction(reader);
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown INVTRANLIST element: {0}", reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the investment transaction list");
                        break parse;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "parseInvestmentTransactionList");
    }

    private void parseInvestmentTransaction(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseInvestmentTransaction");

        final QName parsingElement = reader.getName();

        final OfxTransaction tran = new OfxTransaction();

        // extract the investment transaction type from the element name
        switch (parsingElement.toString()) {
            case BUYMF:
            case BUYOTHER:
            case BUYSTOCK:
                tran.transactionType = TransactionType.BUYSHARE;
                break;
            default:
                logger.log(Level.WARNING, "Unknown investment transaction type: {0}", parsingElement.toString());
                break;
        }

        parse:
        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case DTSETTLE:
                            tran.setDatePosted(parseDate(reader.getElementText()));
                            break;
                        case DTTRADE:
                            tran.setDateUser(parseDate(reader.getElementText()));
                            break;
                        case TOTAL: // total of the investment transaction
                            tran.setAmount(parseAmount(reader.getElementText()));
                            break;
                        case FITID:
                            tran.setTransactionID(reader.getElementText());
                            break;
                        case UNIQUEID:  // the security for the transaction
                            tran.setSecurityId(reader.getElementText());
                            break;
                        case UNITS:
                            tran.setUnits(parseAmount(reader.getElementText()));
                            break;
                        case UNITPRICE:
                            tran.setUnitPrice(parseAmount(reader.getElementText()));
                            break;
                        case COMMISSION:
                            tran.setCommission(parseAmount(reader.getElementText()));
                            break;
                        case SUBACCTSEC:
                            tran.subAccountSec = reader.getElementText();
                            break;
                        case SUBACCTFROM:
                        case SUBACCTTO:
                        case SUBACCTFUND:
                            tran.subAccount = reader.getElementText();
                            break;
                        case CHECKNUM:
                            tran.setCheckNumber(reader.getElementText());
                            break;
                        case NAME:
                        case PAYEE: // either PAYEE or NAME will be used
                            tran.setPayee(reader.getElementText().replaceAll(EXTRA_SPACE_REGEX, " ").trim());
                            break;
                        case MEMO:
                            tran.setMemo(reader.getElementText().replaceAll(EXTRA_SPACE_REGEX, " ").trim());
                            break;
                        case CATEGORY:  // Chase bank mucking up the OFX standard
                            break;
                        case SIC:
                            tran.sic = reader.getElementText();
                            break;
                        case REFNUM:
                            tran.refNum = reader.getElementText();
                            break;
                        case PAYEEID:
                            tran.payeeId = reader.getElementText().replaceAll(EXTRA_SPACE_REGEX, " ").trim();
                            break;
                        case CURRENCY:
                            tran.currency = reader.getElementText();
                            break;
                        case ORIGCURRENCY:
                            tran.currency = reader.getElementText();
                            break;
                        case BUYTYPE:
                        case INVBUY:    // consume
                        case INVTRAN:
                        case SECID:
                        case UNIQUEIDTYPE:
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown investment transaction element: {0}",
                                    reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        //logger.fine("Found the end of the investment transaction");
                        break parse;
                    }
                default:
            }
        }

        bank.addTransaction(tran);

        logger.exiting(OfxV2Parser.class.getName(), "parseInvestmentTransaction");
    }

    /**
     * Parses a STMTTRN element
     *
     * @param reader shared XMLStreamReader
     * @throws XMLStreamException XML parsing error has occurred
     */
    private void parseBankTransaction(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseBankTransaction");

        final QName parsingElement = reader.getName();

        final OfxTransaction tran = new OfxTransaction();

        parse:
        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case TRNTYPE:
                            tran.transactionTypeDescription = reader.getElementText();
                            break;
                        case DTPOSTED:
                            tran.setDatePosted(parseDate(reader.getElementText()));
                            break;
                        case DTUSER:
                            tran.setDateUser(parseDate(reader.getElementText()));
                            break;
                        case TRNAMT:
                            tran.setAmount(parseAmount(reader.getElementText()));
                            break;
                        case FITID:
                            tran.setTransactionID(reader.getElementText());
                            break;
                        case CHECKNUM:
                            tran.setCheckNumber(reader.getElementText());
                            break;
                        case NAME:
                        case PAYEE: // either PAYEE or NAME will be used
                            tran.setPayee(reader.getElementText().replaceAll(EXTRA_SPACE_REGEX, " ").trim());
                            break;
                        case MEMO:
                            tran.setMemo(reader.getElementText().replaceAll(EXTRA_SPACE_REGEX, " ").trim());
                            break;
                        case CATEGORY:  // Chase bank mucking up the OFX standard
                            break;
                        case SIC:
                            tran.sic = reader.getElementText();
                            break;
                        case REFNUM:
                            tran.refNum = reader.getElementText();
                            break;
                        case PAYEEID:
                            tran.payeeId = reader.getElementText().replaceAll(EXTRA_SPACE_REGEX, " ").trim();
                            break;
                        case CURRENCY:
                            tran.currency = reader.getElementText();
                            break;
                        case ORIGCURRENCY:
                            tran.currency = reader.getElementText();
                            break;
                        case SUBACCTFUND: // transfer into / out off an investment account
                            tran.subAccount = reader.getElementText();
                            break;
                        case STMTTRN:   // consume, occurs with an investment account transfer
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown STMTTRN element: {0}", reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the bank transaction");
                        break parse;
                    }
                default:
            }
        }

        bank.addTransaction(tran);

        logger.exiting(OfxV2Parser.class.getName(), "parseBankTransaction");
    }

    /**
     * Parses a BANKACCTFROM element
     *
     * @param reader shared XMLStreamReader
     * @throws XMLStreamException XML parsing error has occurred
     */
    private void parseAccountInfo(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseAccountInfo");

        final QName parsingElement = reader.getName();

        parse:
        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case BANKID:
                        case BROKERID:  // normally a URL per the OFX specification
                            bank.bankId = reader.getElementText();
                            break;
                        case ACCTID:
                            bank.accountId = reader.getElementText();
                            break;
                        case ACCTTYPE:
                            bank.accountType = reader.getElementText();
                            break;
                        case BRANCHID:
                            bank.branchId = reader.getElementText();
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown BANKACCTFROM element: {0}", reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the bank and account info aggregate");
                        break parse;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "parseAccountInfo");
    }

    /**
     * Parses a LEDGERBAL element
     *
     * @param reader shared XMLStreamReader
     * @throws XMLStreamException XML parsing error has occurred
     */
    private void parseLedgerBalance(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseLedgerBalance");

        final QName parsingElement = reader.getName();

        parse:
        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case BALAMT:
                            bank.ledgerBalance = parseAmount(reader.getElementText());
                            break;
                        case DTASOF:
                            bank.ledgerBalanceDate = parseDate(reader.getElementText());
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown ledger balance information {0}", reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the ledger balance aggregate");
                        break parse;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "parseLedgerBalance");
    }

    /**
     * Parses a AVAILBAL element
     *
     * @param reader shared XMLStreamReader
     * @throws XMLStreamException XML parsing error has occurred
     */
    private void parseAvailableBalance(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseAvailableBalance");

        final QName parsingElement = reader.getName();

        parse:
        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case BALAMT:
                            bank.availBalance = parseAmount(reader.getElementText());
                            break;
                        case DTASOF:
                            bank.availBalanceDate = parseDate(reader.getElementText());
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown AVAILBAL element {0}", reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the available balance aggregate");
                        break parse;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "parseAvailableBalance");
    }

    /**
     * Parses a SIGNONMSGSRSV1 element
     *
     * @param reader shared XMLStreamReader
     * @throws XMLStreamException XML parsing error has occurred
     */
    private void parseSignOnMessageSet(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseSignOnMessageSet");

        final QName parsingElement = reader.getName();

        parse:
        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case LANGUAGE:
                            language = reader.getElementText();
                            break;
                        case STATUS:
                            parseSignOnStatus(reader);
                            break;
                        case FI:
                        case FID:
                        case ORG:
                        case INTUBID:
                        case INTUUSERID:
                        default:
                            break;
                    }

                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the sign-on message set aggregate");
                        break parse;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "parseSignOnMessageSet");
    }

    /**
     * Parses a STATUS element from the SignOn element
     *
     * @param reader shared XMLStreamReader
     * @throws XMLStreamException XML parsing error has occurred
     */
    private void parseSignOnStatus(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseSignOnStatus");

        final QName parsingElement = reader.getName();

        parse:
        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case CODE:
                            try {
                                statusCode = Integer.parseInt(reader.getElementText());
                            } catch (final NumberFormatException ex) {
                                logger.log(Level.SEVERE, ex.getLocalizedMessage(), ex);
                            }
                            break;
                        case SEVERITY:
                            statusSeverity = reader.getElementText();
                            break;
                        case MESSAGE:   // consume it, not used
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown STATUS element {0}", reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the statusCode response");
                        break parse;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "parseSignOnStatus");
    }

    /**
     * Parses a STATUS element from the statement element
     *
     * @param reader shared XMLStreamReader
     * @throws XMLStreamException XML parsing error has occurred
     */
    private void parseStatementStatus(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "parseSignOnStatus");

        final QName parsingElement = reader.getName();

        parse:
        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        case CODE:
                            try {
                                bank.statusCode = Integer.parseInt(reader.getElementText());
                            } catch (final NumberFormatException ex) {
                                logger.log(Level.SEVERE, ex.getLocalizedMessage(), ex);
                            }
                            break;
                        case SEVERITY:
                            bank.statusSeverity = reader.getElementText();
                            break;
                        case MESSAGE:
                            bank.statusMessage = reader.getElementText();
                            break;
                        default:
                            logger.log(Level.WARNING, "Unknown STATUS element {0}", reader.getLocalName());
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.fine("Found the end of the statement status response");
                        break parse;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "parseStatementStatus");
    }

    /**
     * Consumes an element that will not be used
     *
     * @param reader shared XMLStreamReader
     * @throws XMLStreamException XML parsing error has occurred
     */
    private void consumeElement(final XMLStreamReader reader) throws XMLStreamException {
        logger.entering(OfxV2Parser.class.getName(), "consumeElement");

        final QName parsingElement = reader.getName();

        parse:
        while (reader.hasNext()) {
            final int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    switch (reader.getLocalName()) {
                        default:
                            break;
                    }

                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (reader.getName().equals(parsingElement)) {
                        logger.finest("Found the end of consumed element " + reader.getName());
                        break parse;
                    }
                default:
            }
        }

        logger.exiting(OfxV2Parser.class.getName(), "consumeElement");
    }

    public OfxBank getBank() {
        logger.info("OFX Status was: " + statusCode);
        logger.info("Status Level was: " + statusSeverity);
        logger.info("File language was: " + language);

        return bank;
    }

    int getStatusCode() {
        return statusCode;
    }

    String getStatusSeverity() {
        return statusSeverity;
    }

    public String getLanguage() {
        return language;
    }
}
