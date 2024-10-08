/*
 * Copyright (c) 2002, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */


/*
 * @test
 *
 * @summary converted from VM Testbase nsk/jdb/unwatch/unwatch002.
 * VM Testbase keywords: [jpda, jdb]
 * VM Testbase readme:
 * DECSRIPTION
 * A positive test case for the 'unwatch all <class id>.<field name>' command.
 * There are two test cases:
 *  - unwatch all for the fields defined in class,
 *  - unwatch all for the fields defined in inner class.
 * At first phase of testing the full watch is set for all checked fields with
 * "watch all" command. Then debugged application invokes the methods (updateFields)
 * in which all checked fields participate in assigned expressions as well as are
 * assigned to. Thus the jdb should report the access event and modification event
 * for the fields.
 * At seconds phase of testing all watch monitors are deleted with the tested
 * command. Then updateFields methods are invoked in debuggee again.
 * The test passes jdb reports only once an access and modification events for
 * every checked fields. Correct report message in jdb stdout should contain full
 * name of the field and "access encountered" or "will be" words.
 * The test consists of two program:
 *   watch002.java - launches jdb and debuggee, writes commands to jdb, reads the jdb output,
 *   watch002a.java - the debugged application.
 * COMMENTS
 *
 * @library /vmTestbase
 *          /test/lib
 * @build nsk.jdb.unwatch.unwatch002.unwatch002a
 * @run driver
 *      nsk.jdb.unwatch.unwatch002.unwatch002
 *      -arch=${os.family}-${os.simpleArch}
 *      -waittime=5
 *      -debugee.vmkind=java
 *      -transport.address=dynamic
 *      -jdb=${test.jdk}/bin/jdb
 *      -java.options="${test.vm.opts} ${test.java.opts}"
 *      -workdir=.
 *      -debugee.vmkeys="${test.vm.opts} ${test.java.opts}"
 */

package nsk.jdb.unwatch.unwatch002;

import nsk.share.*;
import nsk.share.jdb.*;

import java.io.*;
import java.util.*;

public class unwatch002 extends JdbTest {

    public static void main (String argv[]) {
        debuggeeClass =  DEBUGGEE_CLASS;
        firstBreak = FIRST_BREAK;
        lastBreak = LAST_BREAK;
        new unwatch002().runTest(argv);
    }

    static final String PACKAGE_NAME       = "nsk.jdb.unwatch.unwatch002";
    static final String TEST_CLASS         = PACKAGE_NAME + ".unwatch002";
    static final String DEBUGGEE_CLASS     = TEST_CLASS + "a";
    static final String DEBUGGEE_CLASS2    = DEBUGGEE_CLASS + "$CheckedFields";
    static final String FIRST_BREAK        = DEBUGGEE_CLASS + ".main";
    static final String LAST_BREAK         = DEBUGGEE_CLASS + ".breakHere";
    static final String expectedPrompt     = "main[1]";

    static String[] checkedFields  = { "FS1" };
    static String[] checkedFields2 = { "FT1", "FV1" };

    protected void runCases() {
        String[] reply;
        Paragrep grep;
        int count;
        Vector v;
        String found;

        jdb.setBreakpointInMethod(LAST_BREAK);

        reply = jdb.receiveReplyFor(JdbCommand.fields + DEBUGGEE_CLASS);

        reply = jdb.receiveReplyFor(JdbCommand.fields + DEBUGGEE_CLASS2);

        watchFields (DEBUGGEE_CLASS, checkedFields);
        watchFields (DEBUGGEE_CLASS2, checkedFields2);

//        jdb.contToExit((checkedFields.length *2)  + (checkedFields2.length *2) + 2);
        for (int i = 0; i < (checkedFields.length *2 + checkedFields2.length*2 + 2); i++) {
            reply = jdb.receiveReplyForWithMessageWait(JdbCommand.cont, expectedPrompt);
        }

        unwatchFields (DEBUGGEE_CLASS, checkedFields);
        unwatchFields (DEBUGGEE_CLASS2, checkedFields2);

        // excessive number of cont commands in case if unwatch command does not work.
        jdb.contToExit(checkedFields.length*2 + checkedFields2.length*2 + 1);

        reply = jdb.getTotalReply();
        if (!checkFields (DEBUGGEE_CLASS, reply, checkedFields)) {
            success = false;
        }
        if (!checkFields (DEBUGGEE_CLASS2, reply, checkedFields2)) {
            success = false;
        }
    }

    private void watchFields (String className, String[] checkedFields) {
        String[] reply;

        for (int i = 0; i < checkedFields.length; i++) {
            reply = jdb.receiveReplyFor(JdbCommand.watch + " all " + className + "." + checkedFields[i]);
        }

    }

    private void unwatchFields (String className, String[] checkedFields) {
        String[] reply;

        for (int i = 0; i < checkedFields.length; i++) {
            reply = jdb.receiveReplyFor(JdbCommand.unwatch + " all " + className + "." + checkedFields[i]);
        }

    }

    private boolean checkFields (String className, String[] reply, String[] checkedFields) {
        Paragrep grep;
        String found;
        boolean result = true;
        int count;
        Vector v = new Vector();

        grep = new Paragrep(reply);
        v.add("access encountered");
        for (int i = 0; i < checkedFields.length; i++) {
            v.removeAllElements();
            v.add("access encountered");
            v.add(className + "." + checkedFields[i]);

            count = grep.find(v);
            if (count != 1) {
                log.complain("jdb reported wrong number of access to the field " + className + "." + checkedFields[i]);
                log.complain("Should be 1, reported: " + count);
                result = false;
            }

            v.removeAllElements();
            v.add(className + "." + checkedFields[i]);
            v.add("will be instance");

            count = grep.find(v);
            if (count != 1) {
                log.complain("jdb reported wrong number of modification of the field " + className + "." + checkedFields[i]);
                log.complain("Should be 1, reported: " + count);
                result = false;
            }

        }
        return result;
    }
}
