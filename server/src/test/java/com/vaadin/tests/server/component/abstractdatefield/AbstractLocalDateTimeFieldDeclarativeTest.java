/*
 * Copyright 2000-2016 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.tests.server.component.abstractdatefield;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.junit.Test;

import com.vaadin.shared.ui.datefield.DateTimeResolution;
import com.vaadin.tests.server.component.abstractfield.AbstractFieldDeclarativeTest;
import com.vaadin.ui.AbstractLocalDateTimeField;

/**
 * Abstract test class which contains tests for declarative format for
 * properties that are common for AbstractDateField.
 * <p>
 * It's an abstract so it's not supposed to be run as is. Instead each
 * declarative test for a real component should extend it and implement abstract
 * methods to be able to test the common properties. Components specific
 * properties should be tested additionally in the subclasses implementations.
 *
 * @author Vaadin Ltd
 *
 */
public abstract class AbstractLocalDateTimeFieldDeclarativeTest<T extends AbstractLocalDateTimeField>
        extends AbstractFieldDeclarativeTest<T, LocalDateTime> {

    protected DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);

    @Override
    public void valueDeserialization()
            throws InstantiationException, IllegalAccessException {
        LocalDateTime value = LocalDateTime.of(2003, 02, 27, 10, 37, 43);
        String design = String.format("<%s value='%s'/>", getComponentTag(),
                DATE_FORMATTER.format(value));

        T component = getComponentClass().newInstance();
        component.setValue(value);

        testRead(design, component);
        testWrite(design, component);
    }

    @Test
    public void abstractDateFieldAttributesDeserialization()
            throws InstantiationException, IllegalAccessException {
        boolean showIsoWeeks = true;
        LocalDateTime end = LocalDateTime.of(2019, 02, 27, 10, 37, 43);
        LocalDateTime start = LocalDateTime.of(2001, 02, 27, 23, 12, 34);
        String dateOutOfRange = "test date out of range";
        DateTimeResolution resolution = DateTimeResolution.HOUR;
        String dateFormat = "test format";
        boolean lenient = true;
        String parseErrorMsg = "test parse error";
        String design = String.format(
                "<%s show-iso-week-numbers range-end='%s' range-start='%s' "
                        + "date-out-of-range-message='%s' resolution='%s' "
                        + "date-format='%s' lenient parse-error-message='%s'/>",
                getComponentTag(), DATE_FORMATTER.format(end),
                DATE_FORMATTER.format(start), dateOutOfRange,
                resolution.name().toLowerCase(Locale.ENGLISH), dateFormat,
                parseErrorMsg);

        T component = getComponentClass().newInstance();

        component.setShowISOWeekNumbers(showIsoWeeks);
        component.setRangeEnd(end);
        component.setRangeStart(start);
        component.setDateOutOfRangeMessage(dateOutOfRange);
        component.setResolution(resolution);
        component.setDateFormat(dateFormat);
        component.setLenient(lenient);
        component.setParseErrorMessage(parseErrorMsg);

        testRead(design, component);
        testWrite(design, component);
    }

    @Override
    public void readOnlyValue()
            throws InstantiationException, IllegalAccessException {
        LocalDateTime value = LocalDateTime.of(2003, 02, 27, 23, 12, 34);
        String design = String.format("<%s value='%s' readonly/>",
                getComponentTag(), DATE_FORMATTER.format(value));

        T component = getComponentClass().newInstance();
        component.setValue(value);
        component.setReadOnly(true);

        testRead(design, component);
        testWrite(design, component);
    }

}
