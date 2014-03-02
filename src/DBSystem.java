import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Scanner;

import com.foundationdb.sql.StandardException;
import com.foundationdb.sql.parser.*;


public class DBSystem {
	private int pageSize, numPages;
	private String path, configFile;
	private ArrayList<Table> tables = new ArrayList<Table>();	
	private MainMemory m;
	private String havingExpr = "", whereExpr = "";
	
	public void readConfig(String configFilePath) throws IOException {
		File file = new File(configFilePath);
		configFile = configFilePath;
		Scanner sc = null;
		String colName, colDataType;
		
		try {
			sc = new Scanner(file);
			//TODO PAGESIZE or PAGE_SIZE??
			sc.next("PAGE_SIZE");
			pageSize = sc.nextInt();
			
			sc.next("NUM_PAGES");
			numPages = sc.nextInt();

			sc.next("PATH_FOR_DATA");
			path = sc.next();
			
			while(sc.hasNext("BEGIN")) {
				sc.next("BEGIN");
				Table t = new Table(sc.next());
				tables.add(t);
				//System.out.println(t.getName());
				FileWriter f = new FileWriter(path + t.getName() + ".data"); 
				boolean isFirst = true;
				while(!(sc.hasNext("END"))){
					colName = sc.next();
					colName = colName.substring(0, colName.length() - 1);
					colDataType = sc.next();
					//System.out.println(colName.substring(0,colName.length()-1));
					//TODO create <table>.data file
					t.addAttr(new Attribute(colName, colDataType));
					if(isFirst) {
						isFirst = false;
					} else {
						f.write(",");
					}
					f.write(colName + ":" + colDataType);
				}
				sc.next("END");
				f.close();
			}
			
			m = new MainMemory(numPages);
		}
		catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		finally {
			if (sc !=  null) {
				sc.close();
			}
		}
	}

	public void populateDBInfo() {
		File file;
		Scanner sc = null;
		int size, start, count;
		String l;
		
		for (Table t : tables) {
			size = 0;
			start = 0;
			count = 0;
			//file = new File(path + t.getName() + ".csv");
			file = new File(t.getName() + ".csv");
			//TODO add path
			try {
				sc = new Scanner(file);
				while(sc.hasNextLine()) {
					l = sc.nextLine();
					if(count == 0 && l.length() > 0)
						t.addPage(start, pageSize);
					if(l.length() + size < pageSize) {
						size += l.length();
						t.getLastPage().addRecord(l);
					}
					else {
						//new page for table
						t.addPage(count, pageSize);
						t.getLastPage().addRecord(l);
						start = count;
						size = l.length();
					}
					count++;
				}
			}
			catch (FileNotFoundException e) {
				e.printStackTrace();
			}
			finally {
				if (sc !=  null) {
					sc.close();
				}
			}	
		}
	}
	
	public String getRecord (String tableName, int recordId) {
		int index = -1;
		for (Table t : tables) {
			if (t.getName().equals(tableName)) {
				index = tables.indexOf(t);
				break;
			}
		}
		
		Page pg = tables.get(index).getPage(recordId);
		//System.out.println(pg.getTableName() + " " + pg.getStartId());
		System.out.println(m.getPage(pg));
		return pg.getRecord(recordId);
	}
		
	public void insertRecord(String tableName, String record) throws IOException {
		int index = -1;
		Page pg;
		for (Table t : tables) {
			if(t.getName().equals(tableName)) {
				index = tables.indexOf(t);
				break;
			}
		}
		
		pg = tables.get(index).getLastPage();
		if(record.length() >= pg.getFreeSpace()) {
			tables.get(index).addPage(pg.getEndId() + 1, pageSize);
			pg = tables.get(index).getLastPage();
		}
		m.getPage(pg);
		pg.addRecord(record);
		FileWriter f = new FileWriter(path + tableName + ".csv",true);
        f.append(record + '\n');
        f.close();
	}
	
	public void queryType (String query) throws IOException {
		String s[] = query.trim().split("\\s+");
		if (s[0].equalsIgnoreCase("CREATE")) {
			createCommand(query);
		} else if (s[0].equalsIgnoreCase("SELECT")) {
			selectCommand(query);
		}
		else {
			System.out.println("Query invalid");
		}
	}
	
	public void createCommand (String query) throws IOException {
		SQLParser parser = new SQLParser();
        StatementNode node;
		try {
			node = parser.parseStatement(query);
			CreateTableNode createNode = (CreateTableNode) node;
			
			TableElementList elem = createNode.getTableElementList();
			
			if (getTable(createNode.getFullName()) != null) {
				System.out.println("Query Invalid");
			} else {
				Table table = new Table(createNode.getFullName());
				System.out.println("Querytype:create\nTablename:" + table.getName());
				FileWriter file1 = new FileWriter(path + table.getName() + ".data");
				FileWriter file2 = new FileWriter(path + table.getName() + ".csv",true);
				FileWriter file3 = new FileWriter(configFile,true);
				
				file3.append("BEGIN\n" + table.getName() + "\n");
				boolean isFirst = true;
				System.out.print("Attributes:");
				for (TableElementNode t : elem) {
					if(isFirst) {
						isFirst = false;
					} else {
						System.out.print(",");
						file1.write(",");
					}
					ColumnDefinitionNode c = (ColumnDefinitionNode) t;

					String type = c.getType().getSQLstring();
					if (type.equalsIgnoreCase("DOUBLE"))
						type = "FLOAT";
					table.addAttr(new Attribute (c.getName(), type));
					
					System.out.print(c.getName() + " " + type);
					file1.write(c.getName() + ":" + type);
					file3.append(c.getName() + ", " + type + "\n");

				}
				System.out.println("\n");
				tables.add(table);
				file3.append("END\n");
				
				file1.close();
				file2.close();
				file3.close();
			}
			
		} catch (StandardException e) {
			e.printStackTrace();
		}
	}
	
	public void selectCommand (String query) {
		SQLParser parser = new SQLParser();
        StatementNode node;
        havingExpr = "";
        whereExpr = "";
		try {
			//System.out.println(query);
			node = parser.parseStatement(query);
			//node.treePrint();
			//System.out.println();
		
	    	ArrayList<String> fromTables = new ArrayList<String>();
	    	ArrayList<String> columns = new ArrayList<String>();
	    	ArrayList<String> orderColumns = new ArrayList<String>();
	    	ArrayList<String> groupColumns = new ArrayList<String>();
	    	
	    	SelectNode selNode = (SelectNode) ((CursorNode)node).getResultSetNode();
	    	FromList fList = selNode.getFromList();
			boolean isDistinct = selNode.isDistinct();
			
			for (FromTable t : fList) {
				if (getTable(t.getTableName().getTableName()) == null) {
					System.out.println("Query Invalid");
					return;
				}
				fromTables.add(t.getTableName().getTableName());
			}
			//TODO null condition
			
			ResultColumnList colList = selNode.getResultColumns();
			
			boolean allCol = colList.get(0).getNodeType() == NodeTypes.ALL_RESULT_COLUMN;
			if(!allCol) {
				for (ResultColumn col : colList) {
					columns.add(col.getName());
					//System.out.println(col.getName());
				}
			}
			if(!areColumns(fromTables, columns)) {
				System.out.println("Query Invalid");
				return;
			}
			
			OrderByList orderList = ((CursorNode)node).getOrderByList();
			if(orderList != null) {
				for (OrderByColumn col : orderList) {
					orderColumns.add(col.getExpression().getColumnName());
				}
			}
			if(!areColumns(fromTables, orderColumns)) {
				System.out.println("Query Invalid");
				return;
			}
			
			boolean valid = true;
			ValueNode have = selNode.getHavingClause();
			if(have != null) {
				valid = validateClause(have, fromTables);
				havingExpr = whereExpr;
				whereExpr = "";
			}
			
			ValueNode where = selNode.getWhereClause();
			if(valid && where != null)
				valid &= validateClause(where, fromTables);
			//System.out.println(valid);
			
			GroupByList groupList = selNode.getGroupByList();
			if(groupList != null) {
				for(GroupByColumn col : groupList) {
					groupColumns.add(col.getColumnName());
				}
			}
			
			if (!valid)
				System.out.println("Query Invalid");
			else {
				//PRINT QUERY TOKENS
				printSelect(isDistinct, fromTables, allCol, columns, orderColumns, groupColumns);
			}
		} catch (StandardException e) {
			e.printStackTrace();
		}
	}
	
	private void printSelect(boolean isDistinct, ArrayList<String> fromTables, boolean allCol, ArrayList<String> columns, ArrayList<String> orderColumns, ArrayList<String> groupColumns) {
		System.out.print("Querytype:select\nTablename:");
		printStringArray(fromTables);
		
		System.out.print("Columns:");
		if(allCol) {
			//print all column names
			for (String tb : fromTables) {
				Table t = getTable(tb);
				for (int i = 0; i < t.numColumns(); i++) {
					columns.add(t.getColumn(i).getName());
				}
			}
		}
		printStringArray(columns);
		
		System.out.print("Distinct:");
		if(!isDistinct)
			System.out.println("NA");
		else
			printStringArray(columns);
		
		System.out.print("Condition:");
		if(whereExpr.isEmpty())
			System.out.println("NA");
		else
			System.out.println(whereExpr);
		
		System.out.print("Orderby:");
		if (orderColumns.size() == 0)
			System.out.println("NA");
		else
			printStringArray(orderColumns);
		
		System.out.print("Groupby:");
		if (groupColumns.size() == 0)
			System.out.println("NA");
		else
			printStringArray(groupColumns);

		System.out.print("Having:");
		if(havingExpr.isEmpty())
			System.out.println("NA");
		else
			System.out.println(havingExpr);
		
		System.out.println();
	}
	
	private void printStringArray (ArrayList<String> array) {
		boolean first = true;
		for (String s : array) {
			if (first) {
				first = false;
			}
			else {
				System.out.print(", ");
			}
			System.out.print(s);
		}
		System.out.println();
	}
	
	private boolean validateClause (ValueNode node, ArrayList<String> fromTables) {
		if (node.getNodeType() >= NodeTypes.BINARY_EQUALS_OPERATOR_NODE && node.getNodeType() <= NodeTypes.BINARY_NOT_EQUALS_OPERATOR_NODE) {
			BinaryOperatorNode b = (BinaryOperatorNode) node;
			
			ValueNode left = b.getLeftOperand();
			String leftType = null;
			boolean found;
			if (left.getNodeType() == NodeTypes.COLUMN_REFERENCE) {
				//System.out.println("left column");
				String col = left.getColumnName();
				whereExpr += col;
				
				found = false;
				for (String tb : fromTables) {
					Table t = getTable(tb);
					Attribute a = t.getColumn(col);
					if(a != null) {
						found = true;
						leftType = a.getDataTypeName();
						break;
					}
				}
				if(!found)
					return false;
			} else if (left.getNodeType() >= NodeTypes.DECIMAL_CONSTANT_NODE && left.getNodeType() <= NodeTypes.VARCHAR_CONSTANT_NODE) {
				leftType = ((ConstantNode) left).getType().getSQLstring();
				if (leftType.equals("INTEGER"))
					whereExpr += Integer.toString((int) ((ConstantNode) left).getValue());
				else if (leftType.equals("DOUBLE"))
					whereExpr += Float.toString((int) ((ConstantNode) left).getValue());
			} else if (left.getNodeType() == NodeTypes.CHAR_CONSTANT_NODE){
				leftType = "VARCHAR";
				whereExpr += "'" + ((ConstantNode) left).getValue() + "'";
			} else
				return false;

			whereExpr += " " + b.getOperator() + " ";
			
			ValueNode right = b.getRightOperand();
			String rightType = null;
			if (right.getNodeType() == NodeTypes.COLUMN_REFERENCE) {
				String col = right.getColumnName();
				whereExpr += col;
				
				found = false;
				for (String tb : fromTables) {
					Table t = getTable(tb);
					Attribute a = t.getColumn(col);
					if(a != null) {
						found = true;
						rightType = a.getDataTypeName();
						break;
					}
				}
				if(!found)
					return false;

			} else if (right.getNodeType() >= NodeTypes.DECIMAL_CONSTANT_NODE && right.getNodeType() <= NodeTypes.VARCHAR_CONSTANT_NODE) {
				rightType = ((ConstantNode) right).getType().getSQLstring();
				if (rightType.equals("INTEGER"))
					whereExpr += Integer.toString((int) ((ConstantNode) right).getValue());
				else if (rightType.equals("DOUBLE"))
					whereExpr += Float.toString((int) ((ConstantNode) right).getValue());
			} else if (right.getNodeType() == NodeTypes.CHAR_CONSTANT_NODE){
				rightType = "VARCHAR";
				whereExpr += "'" + ((ConstantNode) right).getValue() + "'";
			} else
				return false;
			
			if(leftType.equals("VARCHAR") && rightType.equals("VARCHAR"))
				System.out.println("sahi");
			if (!(leftType.startsWith("VARCHAR") && rightType.startsWith("VARCHAR"))
					&&
				!((leftType.equals("INTEGER") || leftType.equals("DOUBLE") || leftType.equals("FLOAT")) 
						&& 
				(rightType.equals("INTEGER") || rightType.equals("DOUBLE") || rightType.equals("FLOAT")))) {
				System.out.println("corbgerl");
				return false;
			}
			
		} else if (node.getNodeType() == NodeTypes.AND_NODE || node.getNodeType() == NodeTypes.OR_NODE) {
			BinaryOperatorNode n = (BinaryOperatorNode) node;
			boolean leftValid = validateClause(n.getLeftOperand(), fromTables);
			whereExpr += " " + n.getOperator() + " ";
			boolean rightValid = validateClause(n.getRightOperand(), fromTables);
			return leftValid & rightValid;
		} else if (node.getNodeType() == NodeTypes.LIKE_OPERATOR_NODE) {
			LikeEscapeOperatorNode like = (LikeEscapeOperatorNode) node;
			
			String col = like.getReceiver().getColumnName();
			String colType = null;
			whereExpr += col;
			
			for (String tb : fromTables) {
				int index = -1;
				for (Table t : tables) {
					if (t.getName().equalsIgnoreCase(tb)) {
						index = tables.indexOf(t);
						break;
					}
				}
				if(!tables.get(index).isColumn(col)) {
					return false;
				} else {
					Attribute a = tables.get(index).getColumn(col);
					if (a == null)
						return false;
					colType = a.getDataTypeName();
				}
			}
			
			ConstantNode left = (ConstantNode) like.getLeftOperand();
			if (!(left.getNodeType() == NodeTypes.CHAR_CONSTANT_NODE))
				return false;
			whereExpr += " LIKE '" + left.getValue() + "'";
			
		}
		return true;
	}
	
	private void addTable(Table t) {
		tables.add(t);
	}

	private Table getTable(String name) {
		for (Table t : tables) {
			if (t.getName().equalsIgnoreCase(name))
				return t;
		}
		return null;
	}
	
	public boolean areColumns(ArrayList<String> tbls, ArrayList<String> col) {
		for (String c : col) {
			boolean found = false;
			for (String tb : tbls) {
				int index = -1;
				for (Table t : tables) {
					if (t.getName().equalsIgnoreCase(tb)) {
						index = tables.indexOf(t);
						break;
					}
				}
				if(tables.get(index).isColumn(c)) {
					found = true;
					break;
				}
			}
			if(!found)
				return false;
		}
		return true;
	}
}

