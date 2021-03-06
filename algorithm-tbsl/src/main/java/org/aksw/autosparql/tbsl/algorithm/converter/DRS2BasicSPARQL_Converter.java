package org.aksw.autosparql.tbsl.algorithm.converter;

import java.util.*;
import org.aksw.autosparql.tbsl.algorithm.sem.drs.Complex_DRS_Condition;
import org.aksw.autosparql.tbsl.algorithm.sem.drs.DRS;
import org.aksw.autosparql.tbsl.algorithm.sem.drs.DRS_Condition;
import org.aksw.autosparql.tbsl.algorithm.sem.drs.DRS_Quantifier;
import org.aksw.autosparql.tbsl.algorithm.sem.drs.DiscourseReferent;
import org.aksw.autosparql.tbsl.algorithm.sem.drs.Negated_DRS;
import org.aksw.autosparql.tbsl.algorithm.sem.drs.Simple_DRS_Condition;
import org.aksw.autosparql.tbsl.algorithm.sparql.BasicQueryTemplate;
import org.aksw.autosparql.tbsl.algorithm.sparql.Path;
import org.aksw.autosparql.tbsl.algorithm.sparql.SPARQL_Aggregate;
import org.aksw.autosparql.tbsl.algorithm.sparql.SPARQL_Filter;
import org.aksw.autosparql.tbsl.algorithm.sparql.SPARQL_Having;
import org.aksw.autosparql.tbsl.algorithm.sparql.SPARQL_OrderBy;
import org.aksw.autosparql.tbsl.algorithm.sparql.SPARQL_Pair;
import org.aksw.autosparql.tbsl.algorithm.sparql.SPARQL_PairType;
import org.aksw.autosparql.tbsl.algorithm.sparql.SPARQL_Prefix;
import org.aksw.autosparql.tbsl.algorithm.sparql.SPARQL_Property;
import org.aksw.autosparql.tbsl.algorithm.sparql.SPARQL_QueryType;
import org.aksw.autosparql.tbsl.algorithm.sparql.SPARQL_Term;
import org.aksw.autosparql.tbsl.algorithm.sparql.SPARQL_Triple;
import org.aksw.autosparql.tbsl.algorithm.sparql.Slot;
import org.aksw.autosparql.tbsl.algorithm.sparql.SlotType;


public class DRS2BasicSPARQL_Converter {

    List<Slot> slots;
//    BasicQueryTemplate query;
    List<Integer> usedInts;
    List<Simple_DRS_Condition> unusedConditions;

    public DRS2BasicSPARQL_Converter() {
 //   	query = new BasicQueryTemplate();
    	usedInts = new ArrayList<Integer>();
    }
    
    public void setSlots(List<Slot> ls) {
    	slots = ls;
    }

    // TODO ??
    public List<SPARQL_Property> getProperties(Complex_DRS_Condition cond) {
        List<SPARQL_Property> retVal = new ArrayList<SPARQL_Property>();

        return retVal;
    }

    public BasicQueryTemplate convert(DRS drs,List<Slot> ls) {
        
 //       query = new BasicQueryTemplate();
        slots = ls;
    	        
        return convert(drs, new BasicQueryTemplate(), false);
    }

    private BasicQueryTemplate convert(DRS drs, BasicQueryTemplate temp, boolean negate) {
        
//      System.out.println("--- DRS (before): " + drs); // DEBUG   	
        redundantEqualRenaming(drs); 
        if (!restructureEmpty(drs)) {
        	return null;
        }
        System.out.println("DRS:\n" + drs); // DEBUG
        
    	unusedConditions = new ArrayList<Simple_DRS_Condition>();
        
        for (DRS_Condition condition : drs.getConditions()) {
            convertCondition(condition,temp);
            if (negate) {
            	for (SPARQL_Term term : temp.getSelTerms()) {
            		SPARQL_Filter f = new SPARQL_Filter();
                    f.addNotBound(term);
                    temp.addFilter(f);
            	}
            }
        }
       
        for (Simple_DRS_Condition c : unusedConditions) {
        	if (!temp.getVariablesInConditions().contains(c.getArguments().get(0))) {
        		String v = c.getArguments().get(0).getValue();
        		for (Slot s : slots) {
        			if (s.getAnchor().equals(v) && !s.getSlotType().equals(SlotType.RESOURCE)) {
        				String fresh = v+createFresh();
        				s.setAnchor(fresh);
        				temp.addConditions(new Path(v,"isA",fresh));
        				temp.addSlot(s);
        				break;
        			}
        		}	
        	}
        }
        
        for (DiscourseReferent referent : drs.collectDRs()) {
            if (referent.isMarked()) {
            	SPARQL_Term term = new SPARQL_Term(referent.toString().replace("?",""));
            	term.setIsVariable(true);
            	temp.addSelTerm(term);
            }
            if (referent.isNonexistential()) {
            	SPARQL_Term term = new SPARQL_Term(referent.getValue());
            	term.setIsVariable(true);
            	SPARQL_Filter f = new SPARQL_Filter();
            	f.addNotBound(term);
            	temp.addFilter(f);
            }

            for (Slot s : slots) {
        		if (s.getAnchor().equals(referent.getValue())) {
        			temp.addSlot(s); // query
        			break;
        		}
        	}
        }
        
        if (temp.getSelTerms().size() == 0)
        	temp.setQt(SPARQL_QueryType.ASK);

        return temp;
    }

    private BasicQueryTemplate convertCondition(DRS_Condition condition, BasicQueryTemplate temp) {

    	if (condition.isComplexCondition()) {

            Complex_DRS_Condition complex = (Complex_DRS_Condition) condition;

            DRS restrictor = complex.getRestrictor();
            DRS_Quantifier quant = complex.getQuantifier();
            DRS scope = complex.getScope();

            // call recursively
            for (DRS_Condition cond : restrictor.getConditions()) {
                temp = convertCondition(cond,temp);
            }
            for (DRS_Condition cond : scope.getConditions()) {
                temp = convertCondition(cond,temp);
            }
            // preserve marked referents from restrictor and scope
            Set<DiscourseReferent> tokeep = restrictor.collectDRs();
            tokeep.addAll(scope.collectDRs());
            for (DiscourseReferent dr : tokeep) {
            	if (dr.isMarked()) { 
            		temp.addSelTerm(new SPARQL_Term(dr.getValue()));
            	}
            }
            // add the quantifier at last
            DiscourseReferent ref = complex.getReferent();
            String sref = ref.getValue();
            String fresh;

            switch (quant) {
                case HOWMANY:
                    temp.addSelTerm(new SPARQL_Term(sref, SPARQL_Aggregate.COUNT));
                    break;
                case EVERY:
                    // probably save to ignore // TODO unless in cases like "which actor starred in every movie by spielberg?"
                    // query.addFilter(new SPARQL_Filter(new SPARQL_Term(sref)));
                    break;
                case NO:
                    SPARQL_Filter f = new SPARQL_Filter();
                    f.addNotBound(new SPARQL_Term(sref));
                    temp.addFilter(f);
                    break;
                case FEW: //
                    break;
                case MANY: //
                    break;
                case MOST: //
                    break;
                case SOME: //
                    break;
                case THELEAST:
                	fresh = "c"+createFresh();
                    temp.addSelTerm(new SPARQL_Term(sref, SPARQL_Aggregate.COUNT,fresh));
                    temp.addOrderBy(new SPARQL_Term(fresh, SPARQL_OrderBy.ASC));
                    temp.setLimit(1);
                    break;
                case THEMOST:
                	fresh = "c"+createFresh();
                    temp.addSelTerm(new SPARQL_Term(sref, SPARQL_Aggregate.COUNT,fresh));
                    temp.addOrderBy(new SPARQL_Term(fresh, SPARQL_OrderBy.DESC));
                    temp.setLimit(1);
                    break;
            }
        } else if (condition.isNegatedCondition()) {
            Negated_DRS neg = (Negated_DRS) condition;
            temp = convert(neg.getDRS(), temp, true);

        } else {
            Simple_DRS_Condition simple = (Simple_DRS_Condition) condition;
                       
            String predicate = simple.getPredicate();
            if (predicate.startsWith("SLOT")) {
            	for (Slot s : slots) {
            		if (s.getAnchor().equals(predicate)) {
            			if (simple.getArguments().size() > 1) {
	            			s.setToken(predicate);
	            			predicate = "p" + createFresh();
	           				s.setAnchor(predicate); 
	           				temp.addSlot(s);
            			}
            			else if (simple.getArguments().size() == 1) {
            				s.setAnchor(simple.getArguments().get(0).getValue());
            			} 
            			break;
            		}
            		else if (s.getToken().equals(predicate)) {
            			predicate = s.getAnchor();
            		}
            	}
            }
            
            SPARQL_Property prop = new SPARQL_Property(predicate);
            prop.setIsVariable(true);
            
            boolean literal = false; 
            if (simple.getArguments().size() > 1 && simple.getArguments().get(1).getValue().matches("\\d+")) {
            	literal = true;
            }

            if (predicate.equals("of")) {
            	if (simple.getArguments().size() == 2) {
            		Path p = new Path();
            		p.setStart(simple.getArguments().get(1).getValue());
            		p.setTarget(simple.getArguments().get(0).getValue());
            		temp.addConditions(p);
            	}
            }
            if (predicate.startsWith("p")) {
            	if (simple.getArguments().size() == 2) {
            		Path p = new Path();
            		p.setStart(simple.getArguments().get(0).getValue());
            		p.setVia(predicate);
            		p.setTarget(simple.getArguments().get(1).getValue());
            		temp.addConditions(p);
            	}
                else if (simple.getArguments().size() == 3) {
                        Path p = new Path();
            		p.setStart(simple.getArguments().get(0).getValue());
                        p.setVia(predicate);
                        String newword = null;
                        Slot del = null;
                        for (Slot s : slots) {
                            if (s.getAnchor().equals(simple.getArguments().get(1).getValue())) {
                                newword = s.getWords().get(0);
                                del = s;
                                break;
                            }
                        }
                        if (newword != null) {
                            for (Slot s : slots) {
                                if (s.getAnchor().equals(predicate)) {
                                    boolean date = false;
                                    if (s.getWords().get(0).endsWith(" date")) date = true;
                                    newword = s.getWords().get(0).replace(" date","") + " " + newword; 
                                    if (date) newword += " date";
                                    s.setWords(Arrays.asList(newword));
                                    break;
                                }
                            }
                            if (del != null) slots.remove(del);
                        }
            		p.setTarget(simple.getArguments().get(2).getValue());
            		temp.addConditions(p);
                }
            }
            else if (predicate.equals("count")) {
            	if (simple.getArguments().size() == 1) {
            		temp.addSelTerm(new SPARQL_Term(simple.getArguments().get(0).getValue(), SPARQL_Aggregate.COUNT));
            	}
            	else {
	            	if (!literal) {
	            		temp.addSelTerm(new SPARQL_Term(simple.getArguments().get(0).getValue(), SPARQL_Aggregate.COUNT, simple.getArguments().get(1).getValue()));
	            		return temp;
	            	}
	            	else { // COUNT(?x) AS ?c
	//            		String fresh = "c"+createFresh();
	//            		temp.addSelTerm(new SPARQL_Term(simple.getArguments().get(0).getValue(), SPARQL_Aggregate.COUNT, fresh));
	//            		temp.addFilter(new SPARQL_Filter(
	//            				new SPARQL_Pair(
	//            				new SPARQL_Term(fresh),
	//            				new SPARQL_Term(simple.getArguments().get(1).getValue(),true),
	//            				SPARQL_PairType.EQ)));
	            		temp.addHaving(new SPARQL_Having("COUNT(?"+simple.getArguments().get(0).getValue() + ") = "+simple.getArguments().get(1).getValue()));
	            		return temp;
	            	}
            	}
            } else if (predicate.equals("count_greater")) {
            	temp.addHaving(new SPARQL_Having("COUNT(?"+simple.getArguments().get(0).getValue() + ") > "+simple.getArguments().get(1).getValue()));
        		return temp;
            } else if (predicate.equals("count_less")) {
            	temp.addHaving(new SPARQL_Having("COUNT(?"+simple.getArguments().get(0).getValue() + ") < "+simple.getArguments().get(1).getValue()));
        		return temp;
            } else if (predicate.equals("count_greatereq")) {
            	temp.addHaving(new SPARQL_Having("COUNT(?"+simple.getArguments().get(0).getValue() + ") >= "+simple.getArguments().get(1).getValue()));
        		return temp;
            } else if (predicate.equals("count_lesseq")) {
            	temp.addHaving(new SPARQL_Having("COUNT(?"+simple.getArguments().get(0).getValue() + ") <= "+simple.getArguments().get(1).getValue()));
        		return temp;
            } else if (predicate.equals("count_eq")) {
            	temp.addHaving(new SPARQL_Having("COUNT(?"+simple.getArguments().get(0).getValue() + ") = "+simple.getArguments().get(1).getValue()));
        		return temp;
            } else if (predicate.equals("sum")) {
                temp.addSelTerm(new SPARQL_Term(simple.getArguments().get(1).getValue(), SPARQL_Aggregate.SUM));
                return temp;
            } else if (predicate.equals("greater")) {
            	temp.addFilter(new SPARQL_Filter(
                        new SPARQL_Pair(
                        new SPARQL_Term(simple.getArguments().get(0).getValue(),false),
                        new SPARQL_Term(simple.getArguments().get(1).getValue(),literal),
                        SPARQL_PairType.GT)));
                return temp;
            } else if (predicate.equals("greaterorequal")) {
            	temp.addFilter(new SPARQL_Filter(
                        new SPARQL_Pair(
                        new SPARQL_Term(simple.getArguments().get(0).getValue(),false),
                        new SPARQL_Term(simple.getArguments().get(1).getValue(),literal),
                        SPARQL_PairType.GTEQ)));
                return temp;
            } else if (predicate.equals("less")) {
            	temp.addFilter(new SPARQL_Filter(
                        new SPARQL_Pair(
                        new SPARQL_Term(simple.getArguments().get(0).getValue(),false),
                        new SPARQL_Term(simple.getArguments().get(1).getValue(),literal),
                        SPARQL_PairType.LT)));
                return temp;
            } else if (predicate.equals("lessorequal")) {
            	temp.addFilter(new SPARQL_Filter(
                        new SPARQL_Pair(
                        new SPARQL_Term(simple.getArguments().get(0).getValue(),false),
                        new SPARQL_Term(simple.getArguments().get(1).getValue(),literal),
                        SPARQL_PairType.LTEQ)));
                return temp;
            } else if (predicate.equals("maximum")) {
                temp.addSelTerm(new SPARQL_Term(simple.getArguments().get(0).getValue()));
                temp.addOrderBy(new SPARQL_Term(simple.getArguments().get(0).getValue(), SPARQL_OrderBy.DESC));
                temp.setLimit(1);                
                return temp;
            } else if (predicate.equals("minimum")) {
                temp.addSelTerm(new SPARQL_Term(simple.getArguments().get(0).getValue()));
                temp.addOrderBy(new SPARQL_Term(simple.getArguments().get(0).getValue(), SPARQL_OrderBy.ASC));
                temp.setLimit(1);  
                return temp;
            } else if (predicate.equals("countmaximum")) {
                temp.addSelTerm(new SPARQL_Term(simple.getArguments().get(0).getValue(), SPARQL_Aggregate.COUNT, "c"));
                temp.addOrderBy(new SPARQL_Term("c", SPARQL_OrderBy.DESC)); 
                temp.setLimit(1);
            	return temp;
            } else if (predicate.equals("countminimum")) {
                temp.addSelTerm(new SPARQL_Term(simple.getArguments().get(0).getValue(), SPARQL_Aggregate.COUNT, "c"));
                temp.addOrderBy(new SPARQL_Term("c", SPARQL_OrderBy.DESC));
                temp.setLimit(1);
            	return temp;
            } else if (predicate.equals("equal")) {
            	temp.addFilter(new SPARQL_Filter(
                        new SPARQL_Pair(
                        new SPARQL_Term(simple.getArguments().get(0).getValue(),false),
                        new SPARQL_Term(simple.getArguments().get(1).getValue(),literal),
                        SPARQL_PairType.EQ)));
                return temp;
            }
            else if (predicate.equals("DATE")) {
            	temp.addFilter(new SPARQL_Filter(
            			new SPARQL_Pair(
                        new SPARQL_Term(simple.getArguments().get(0).getValue(),false),
                        new SPARQL_Term("'^"+simple.getArguments().get(1).getValue()+"'",true),
                        SPARQL_PairType.REGEX)));
            }
            else if (predicate.equals("regex")) {
            	temp.addFilter(new SPARQL_Filter(
            			new SPARQL_Pair(
                        new SPARQL_Term(simple.getArguments().get(0).getValue(),false),
                        new SPARQL_Term(simple.getArguments().get(1).getValue(),true),
                        SPARQL_PairType.REGEX)));
            }
            else {
            	if (simple.getArguments().size() == 1) {
            		unusedConditions.add((Simple_DRS_Condition) condition);
	            }
            }

        }
    	
        return temp;
    }


    public void redundantEqualRenaming(DRS drs) {

        Set<Simple_DRS_Condition> equalsConditions = new HashSet<Simple_DRS_Condition>();
        for (Simple_DRS_Condition c : drs.getAllSimpleConditions()) {
        	if(c.getPredicate().equals("equal")) {
        		equalsConditions.add(c);
        	}
        }
        
        DiscourseReferent firstArg;
        DiscourseReferent secondArg;
        boolean firstIsURI;
        boolean secondIsURI;
        
        for (Simple_DRS_Condition c : equalsConditions) {
        
        	firstArg = c.getArguments().get(0);
            secondArg = c.getArguments().get(1);
            firstIsURI = isUri(firstArg.getValue());
            secondIsURI = isUri(secondArg.getValue());

            boolean oneArgIsInt = firstArg.toString().matches("[0..9]") || secondArg.toString().matches("[0..9]");

            drs.removeCondition(c);
            if (firstIsURI) {
                drs.replaceEqualRef(secondArg, firstArg, false);
                for (Slot s : slots) {
                	if (s.getAnchor().equals(secondArg.getValue())) {
                		s.setAnchor(firstArg.getValue());
                	}
                }
            } else if (secondIsURI) {
                drs.replaceEqualRef(firstArg, secondArg, false);
                for (Slot s : slots) {
                	if (s.getAnchor().equals(firstArg.getValue())) {
                		s.setAnchor(secondArg.getValue());
                	}
                }
            } else if (!oneArgIsInt) {
                drs.replaceEqualRef(firstArg, secondArg, false);
                for (Slot s : slots) {
                	if (s.getAnchor().equals(firstArg.getValue())) {
                		s.setAnchor(secondArg.getValue());
                	}
                }
            }
        }
        
        // finally remove all conditions that ended up of form equal(y,y)
        Set<Simple_DRS_Condition> equalEqualsConditions = new HashSet<Simple_DRS_Condition>();
        for (Simple_DRS_Condition c : drs.getAllSimpleConditions()) {
        	if(c.getPredicate().equals("equal") && c.getArguments().get(0).getValue().equals(c.getArguments().get(1).getValue())) {
        		equalEqualsConditions.add(c);
        	}
        }
        for (Simple_DRS_Condition c : equalEqualsConditions) {
        	drs.removeCondition(c);
        }
    }

    private boolean isUri(String arg) {
        return false; // TODO
    }
    
	private int createFresh() {
		
		int fresh = 0;
		for (int i = 0; usedInts.contains(i); i++) {
			fresh = i+1 ;
		}
		usedInts.add(fresh);
		return fresh;
	}
	
    private boolean restructureEmpty(DRS drs) {
    	        
    	Set<Simple_DRS_Condition> emptyConditions = new HashSet<Simple_DRS_Condition>();
        for (Simple_DRS_Condition c : drs.getAllSimpleConditions()) {
        	if(c.getPredicate().equals("empty")) {
        		emptyConditions.add(c);
        	}
        }
        if (emptyConditions.isEmpty()) {
        	return true;
        }
        
        boolean globalsuccess = false;
        for (Simple_DRS_Condition c : emptyConditions) {
        	String nounToExpand = c.getArguments().get(1).getValue();
        	String fallbackNoun = c.getArguments().get(0).getValue();
        	boolean success = false;
        	loop: 
        	for (Simple_DRS_Condition sc : drs.getAllSimpleConditions()) {
        		if (sc.getArguments().size() == 1 && sc.getArguments().get(0).getValue().equals(nounToExpand)) {
        			for (Slot s : slots) {
        				if (s.getAnchor().equals(sc.getPredicate())) {
        					if (s.getSlotType().equals(SlotType.CLASS) || s.getSlotType().equals(SlotType.UNSPEC)) {
        						s.setSlotType(SlotType.PROPERTY); 
        						List<DiscourseReferent> newargs = new ArrayList<DiscourseReferent>();
                    			newargs.add(c.getArguments().get(0));
                    			newargs.add(sc.getArguments().get(0));
                    			sc.setArguments(newargs);
                    			success = true;
                    			globalsuccess = true;
                    			break loop;
        					}
        				}	
        			}
        		}
        	}
        	if (!success) { // do the same for fallbackNoun
        		loop: 
                for (Simple_DRS_Condition sc : drs.getAllSimpleConditions()) {
                	if (sc.getArguments().size() == 1 && sc.getArguments().get(0).getValue().equals(fallbackNoun)) {
                		for (Slot s : slots) {
                			if (s.getAnchor().equals(sc.getPredicate())) {
                				if (s.getSlotType().equals(SlotType.CLASS) || s.getSlotType().equals(SlotType.UNSPEC)) {
                					s.setSlotType(SlotType.PROPERTY); 
                					List<DiscourseReferent> newargs = new ArrayList<DiscourseReferent>();
                           			newargs.add(c.getArguments().get(1));
                           			newargs.add(sc.getArguments().get(0));
                           			sc.setArguments(newargs);
                           			success = true;
                           			globalsuccess = true;
                           			break loop;
                				}
                			}	
                		}
                	}
                }
        	}
        }
        
        if (globalsuccess) {
        	for (Simple_DRS_Condition c : emptyConditions) {
        		drs.removeCondition(c);
        	}
        }
        return globalsuccess;
	}    
}
