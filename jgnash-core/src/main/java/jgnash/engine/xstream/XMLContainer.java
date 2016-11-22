/*
 * jGnash, a personal finance application
 * Copyright (C) 2001-2016 Craig Cavanaugh
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
package jgnash.engine.xstream;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import jgnash.engine.CommodityNode;
import jgnash.engine.Config;
import jgnash.engine.Engine;
import jgnash.engine.ExchangeRate;
import jgnash.engine.RootAccount;
import jgnash.engine.StoredObject;
import jgnash.engine.StoredObjectComparator;
import jgnash.engine.budget.Budget;
import jgnash.engine.recurring.Reminder;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.reflection.PureJavaReflectionProvider;
import com.thoughtworks.xstream.io.xml.KXml2Driver;
import com.thoughtworks.xstream.io.xml.PrettyPrintWriter;

/**
 * Simple object container for StoredObjects that reads and writes an XML file.
 *
 * @author Craig Cavanaugh
 */
class XMLContainer extends AbstractXStreamContainer {

    XMLContainer(final File file) {
        super(file);
    }

    @Override
    void commit() {
        writeXML();
    }

    private synchronized void writeXML() {
        readWriteLock.readLock().lock();

        try {
            releaseFileLock();
            writeXML(objects, file);
        } finally {
            if (!acquireFileLock()) { // lock the file on open
                Logger.getLogger(XMLContainer.class.getName()).severe("Could not acquire the file lock");
            }
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * Writes an XML file given a collection of StoredObjects. TrashObjects and
     * objects marked for removal are not written. If the file already exists,
     * it will be overwritten.
     *
     * @param objects Collection of StoredObjects to write
     * @param file    file to write
     */
    static synchronized void writeXML(final Collection<StoredObject> objects, final File file) {
        Logger logger = Logger.getLogger(XMLContainer.class.getName());

        createBackup(file);

        List<StoredObject> list = new ArrayList<>();

        list.addAll(query(objects, Budget.class));
        list.addAll(query(objects, Config.class));
        list.addAll(query(objects, CommodityNode.class));
        list.addAll(query(objects, ExchangeRate.class));
        list.addAll(query(objects, RootAccount.class));
        list.addAll(query(objects, Reminder.class));

        // remove any objects marked for removal
        list.removeIf(StoredObject::isMarkedForRemoval);

        // sort the list
        list.sort(new StoredObjectComparator());

        logger.info("Writing XML file");

        try (final Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            writer.write("<?fileFormat " + Engine.CURRENT_MAJOR_VERSION + "." + Engine.CURRENT_MINOR_VERSION + "?>\n");

            final XStream xstream = configureXStream(new XStreamOut(new PureJavaReflectionProvider(), new KXml2Driver()));

            try (final ObjectOutputStream out = xstream.createObjectOutputStream(new PrettyPrintWriter(writer))) {
                out.writeObject(list);
                out.flush();     // forcibly flush before letting go of the resources to help older windows systems write correctly
            } catch (final Exception e) {
                logger.log(Level.SEVERE, e.getLocalizedMessage(), e);
            }
        } catch (final IOException e) {
            logger.log(Level.SEVERE, e.getLocalizedMessage(), e);
        }

        logger.info("Writing XML file complete");
    }

    void readXML() {
        try (final FileInputStream fis = new FileInputStream(file);
             Reader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8.name()))) {

            readWriteLock.writeLock().lock();

            final XStream xstream = configureXStream(new XStream(new StoredObjectReflectionProvider(objects),
                    new KXml2Driver()));

            // Filters out any java.sql.Dates that sneaked in when saving from a relational database
            // and forces to a LocalDate
            // TODO: Remove at a later date
            xstream.alias("sql-date", LocalDate.class);

            try (final ObjectInputStream in = xstream.createObjectInputStream(reader);
                 FileLock readLock = fis.getChannel().tryLock(0, Long.MAX_VALUE, true)) {
                if (readLock != null) {
                    in.readObject();
                }
            }

        } catch (final IOException | ClassNotFoundException e) {
            Logger.getLogger(XMLContainer.class.getName()).log(Level.SEVERE, null, e);
        } finally {
            if (!acquireFileLock()) { // lock the file on open
                Logger.getLogger(XMLContainer.class.getName()).severe("Could not acquire the file lock");
            }
            readWriteLock.writeLock().unlock();
        }
    }
}
