/*
 * Copyright (c) 2008 Plausible Labs Cooperative, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of any contributors
 *    may be used to endorse or promote products derived from this
 *    software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.plausiblelabs.mdb;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.healthmarketscience.jackcess.Column;
import com.healthmarketscience.jackcess.Database;
import com.healthmarketscience.jackcess.Table;

/**
 * Handles export of an MS Access database to an SQLite file.
 */
public class AccessExporter {
    /**
     * Create a new exporter with the provided MS Access
     * database
     * 
     * @param db A reference to an Access database.
     */
    public AccessExporter (Database db) {
        this.db = db;
    }
    
    /* XXX: Manual escaping of identifiers. */
    private String escapeIdentifier (final String identifier) {
        return "'" + identifier.replace("'", "''") + "'";
    }
    
    /**
     * Create an SQLite table for the corresponding MS Access table.
     * 
     * @param table MS Access table
     * @param jdbc The SQLite database JDBC connection
     * @throws SQLException 
     */
    private void createTable (final Table table, final Connection jdbc) throws SQLException {
        final List<Column> columns = table.getColumns();
        final StringBuilder stmtBuilder = new StringBuilder();

        /* Create the statement */
        stmtBuilder.append("CREATE TABLE " + escapeIdentifier(table.getName()) + " (");
        
        final int columnCount = columns.size();
        for (int i = 0; i < columnCount; i++) {
            final Column column = columns.get(i);
            
            stmtBuilder.append(escapeIdentifier(column.getName()));
            stmtBuilder.append(" ");
            switch (column.getType()) {
                /* Blob */
                case BINARY:
                case OLE:
                    stmtBuilder.append("BLOB");
                    break;
                
                /* Integers */
                case BOOLEAN:
                case BYTE:
                case INT:
                case LONG:
                    stmtBuilder.append("INTEGER");
                    break;
              
                /* Timestamp */
                case SHORT_DATE_TIME:
                    stmtBuilder.append("DATETIME");
                    break;
               
                /* Floating point */
                case DOUBLE:
                case FLOAT:
                case NUMERIC:
                    stmtBuilder.append("DOUBLE");
                    break;
                
                /* Strings */
                case TEXT:
                case GUID:
                case MEMO:
                    stmtBuilder.append("TEXT");
                    break;

                /* Money -- This can't be floating point, so let's be safe with strings */
                case MONEY:
                    stmtBuilder.append("TEXT");
                    break;

                default:
                    throw new SQLException("Unhandled MS Acess datatype: " + column.getType());
            }
            
            if (i + 1 < columnCount)
                stmtBuilder.append(", ");
        }
        stmtBuilder.append(")");
        
        /* Execute it */
        final Statement stmt = jdbc.createStatement();
        stmt.execute(stmtBuilder.toString());
    }

    /**
     * Iterate over and create SQLite tables for every table defined
     * in the MS Access database.
     * 
     * @param jdbc The SQLite database JDBC connection
     */
    private void createTables (final Connection jdbc) throws IOException, SQLException {
        final Set<String> tableNames = db.getTableNames();
        
        for (String tableName : tableNames) {
            Table table = db.getTable(tableName);
            createTable(table, jdbc);
        }
    }
    
    
    private void populateTable (Table table, Connection jdbc) throws SQLException {
        final List<Column> columns = table.getColumns();
        final StringBuilder stmtBuilder = new StringBuilder();
        final StringBuilder valueStmtBuilder = new StringBuilder();
        
        /* Record the column count */
        final int columnCount = columns.size();
        
        /* Build the INSERT statement (in two pieces simultaneously) */
        stmtBuilder.append("INSERT INTO " + escapeIdentifier(table.getName()) + " (");
        valueStmtBuilder.append("(");
        
        for (int i = 0; i < columnCount; i++) {
            final Column column = columns.get(i);

            /* The column name and the VALUE binding */
            stmtBuilder.append(escapeIdentifier(column.getName()));
            valueStmtBuilder.append("?");
            
            if (i + 1 < columnCount) {
                stmtBuilder.append(", ");
                valueStmtBuilder.append(", ");
            }
        }
        
        /* Now append the VALUES piece */
        stmtBuilder.append(") VALUES ");
        stmtBuilder.append(valueStmtBuilder);
        stmtBuilder.append(")");
        
        /* Create the prepared statement */
        final PreparedStatement prep = jdbc.prepareStatement(stmtBuilder.toString());
        
        /* Kick off the insert spree */
        for (Map<String, Object> row : table) {
            /* Bind all the column values. We let JDBC do type conversion -- is this correct?. */
            for (int i = 0; i < columnCount; i++) {
                final Column column = columns.get(i);
                final Object value = row.get(column.getName());
                
                /* If null, just bail out early and avoid a lot of NULL checking */
                if (value == null) {
                    prep.setObject(i + 1, value);
                    continue;
                }

                /* Perform any conversions */
                switch (column.getType()) {
                    case MONEY:
                        /* Store money as a string. Is there any other valid representation in SQLite? */
                        prep.setString(i + 1, row.get(column.getName()).toString());
                        break;
                    case BOOLEAN:
                        /* The SQLite JDBC driver does not handle boolean values */
                        final boolean bool;
                        final int intVal;

                        /* Determine the value (1/0) */
                        bool = (Boolean) row.get(column.getName());
                        if (bool)
                            intVal = 1;
                        else
                            intVal = 0;
                            
                        /* Store it */
                        prep.setInt(i + 1, intVal);
                        break;
                    default:
                        prep.setObject(i + 1, row.get(column.getName()));
                        break;
                }
                
            }
            
            /* Execute the insert */
            prep.executeUpdate();
        }
    }
    
    /**
     * Iterate over all data and populate the SQLite tables
     * @param jdbc The SQLite database JDBC connection
     * @throws IOException 
     * @throws SQLException 
     */
    private void populateTables (final Connection jdbc) throws IOException, SQLException {
        final Set<String> tableNames = db.getTableNames();
        
        for (String tableName : tableNames) {
            Table table = db.getTable(tableName);
            populateTable(table, jdbc);
        }
    }
    
    /**
     * Export the Access database to the given SQLite JDBC connection.
     * The referenced SQLite database should be empty.
     * 
     * @param jdbc A JDBC connection to a SQLite database.
     * @throws SQLException 
     */
    public void export (final Connection jdbc) throws IOException, SQLException {
        /* Start a transaction */
        jdbc.setAutoCommit(false);
        
        /* Create the tables */
        createTables(jdbc);
        
        /* Populate the tables */
        populateTables(jdbc);
        
        jdbc.commit();
        jdbc.setAutoCommit(true);
    }

    /** MS Access database */
    private final Database db;
}
