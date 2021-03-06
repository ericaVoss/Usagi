/*******************************************************************************
 * Copyright 2014 Observational Health Data Sciences and Informatics
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.ohdsi.usagi.indexBuilding;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.ohdsi.usagi.TargetConcept;
import org.ohdsi.usagi.UsagiSearchEngine;
import org.ohdsi.usagi.ui.Global;
import org.ohdsi.utilities.files.FileSorter;
import org.ohdsi.utilities.files.MultiRowIterator;
import org.ohdsi.utilities.files.MultiRowIterator.MultiRowSet;
import org.ohdsi.utilities.files.ReadCSVFileWithHeader;
import org.ohdsi.utilities.files.Row;

/**
 * Builds the initial Lucene indes used by Usagi
 */
public class BuildIndex {
	// public static String folder = "s:/data/Usagi";
	// public static String termfile = folder + "/Terms.csv";
	// public static String CONCEPT_TYPE_STRING = "C";
	public static String[]	vocabularyIds	= new String[] { "APC", "CPT4", "DRG", "HCPCS", "HES Specialty", "ICD9Proc", "LOINC", "LOINC Hierarchy", "MDC",
			"Multilex", "NUCC", "OPCS4", "Place of Service", "Race", "Revenue Code", "RxNorm", "SNOMED", "Specialty", "UCUM" };

	public static void main(String[] args) {
		// BuildIndex buildIndex = new BuildIndex();
		// buildIndex.process();
		Global.folder = "c:/temp";
		BuildIndex buildIndex = new BuildIndex();
		buildIndex.buildIndex("S:/Data/OMOP Standard Vocabulary V5/Vocabulary5.0-20141013", "S:/Data/LOINC/loinc.csv");
	}

	public void buildIndex(String vocabFolder, String loincFile) {
		JDialog dialog = null;
		JLabel label = null;
		if (Global.frame != null) {
			dialog = new JDialog(Global.frame, "Progress Dialog", false);

			JPanel panel = new JPanel();
			panel.setBorder(BorderFactory.createRaisedBevelBorder());

			JPanel sub = new JPanel();
			sub.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
			sub.setLayout(new BoxLayout(sub, BoxLayout.Y_AXIS));

			sub.add(new JLabel("Building index. This will take a while...."));

			label = new JLabel("Starting");
			sub.add(label);
			panel.add(sub);
			dialog.add(panel);

			dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
			dialog.setSize(300, 75);
			dialog.setLocationRelativeTo(Global.frame);
			dialog.setUndecorated(true);
			dialog.setModal(true);
		}
		BuildThread thread = new BuildThread(vocabFolder, loincFile, label, dialog);
		thread.start();
		if (dialog != null)
			dialog.setVisible(true);
		try {
			thread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private class BuildThread extends Thread {

		private JDialog	dialog;
		private JLabel	label;
		private String	vocabFolder;
		private String	loincFile;

		public BuildThread(String vocabFolder, String loincFile, JLabel label, JDialog dialog) {
			this.vocabFolder = vocabFolder;
			this.loincFile = loincFile;
			this.label = label;
			this.dialog = dialog;
		}

		private void report(String message) {
			if (label != null)
				label.setText(message);
		}

		public void run() {
			// Load LOINC information into memory if user wants to include it in the index:
			Map<String, String> loincToInfo = null;
			if (loincFile != null) {
				report("Loading LOINC additional information");
				loincToInfo = loadLoincInfo(loincFile);
			}
			report("Sorting vocabulary files");
			FileSorter.sort(vocabFolder + "/CONCEPT.csv", new String[] { "CONCEPT_ID" }, new boolean[] { true });
			FileSorter.sort(vocabFolder + "/CONCEPT_SYNONYM.csv", new String[] { "CONCEPT_ID" }, new boolean[] { true });

			report("Adding concepts to index");
			UsagiSearchEngine usagiSearchEngine = new UsagiSearchEngine(Global.folder);
			usagiSearchEngine.createNewMainIndex();

			Iterator<Row> conceptIterator = new ReadCSVFileWithHeader(vocabFolder + "/CONCEPT.csv").iterator();
			Iterator<Row> conceptSynIterator = new ReadCSVFileWithHeader(vocabFolder + "/CONCEPT_SYNONYM.csv").iterator();
			@SuppressWarnings("unchecked")
			MultiRowIterator iterator = new MultiRowIterator("CONCEPT_ID", true, new String[] { "concept", "concept_synonym" }, new Iterator[] {
					conceptIterator, conceptSynIterator });
			Set<String> allowedVocabularies = new HashSet<String>();
			for (String allowedVocabulary : vocabularyIds)
				allowedVocabularies.add(allowedVocabulary);
			while (iterator.hasNext()) {
				MultiRowSet multiRowSet = iterator.next();
				Row conceptRow = multiRowSet.get("concept").get(0);
				if (conceptRow.getCells().size() > 2) // Extra check to catch badly formatted rows (which are in a vocab we don't care about)
					if (conceptRow.get("STANDARD_CONCEPT").equals("S") && allowedVocabularies.contains(conceptRow.get("VOCABULARY_ID"))) {
						for (Row synonymRow : multiRowSet.get("concept_synonym")) {
							TargetConcept concept = new TargetConcept();
							concept.term = synonymRow.get("CONCEPT_SYNONYM_NAME");
							concept.conceptClass = conceptRow.get("CONCEPT_CLASS_ID");
							concept.conceptCode = conceptRow.get("CONCEPT_CODE");
							concept.conceptId = conceptRow.getInt("CONCEPT_ID");
							concept.conceptName = conceptRow.get("CONCEPT_NAME");
							for (String domain : conceptRow.get("DOMAIN_ID").split("/")) {
								if (domain.equals("Obs"))
									domain = "Observation";
								if (domain.equals("Meas"))
									domain = "Measurement";
								concept.domains.add(domain);
							}
							concept.invalidReason = conceptRow.get("INVALID_REASON");
							concept.validEndDate = conceptRow.get("VALID_END_DATE");
							concept.validStartDate = conceptRow.get("VALID_START_DATE");
							concept.vocabulary = conceptRow.get("VOCABULARY_ID");
							if (loincToInfo != null && concept.vocabulary.equals("LOINC")) {
								String info = loincToInfo.get(concept.conceptCode);
								if (info != null)
									concept.additionalInformation = info;
							}
							if (concept.additionalInformation == null)
								concept.additionalInformation = "";
							usagiSearchEngine.addConceptToIndex(concept);
						}
					}
			}
			usagiSearchEngine.close();
			if (dialog != null)
				dialog.setVisible(false);
		}

		private Map<String, String> loadLoincInfo(String loincFile) {
			Map<String, String> loincToInfo = new HashMap<String, String>();
			for (Row row : new ReadCSVFileWithHeader(loincFile)) {
				StringBuilder info = new StringBuilder();
				info.append("LOINC concept information\n\n");
				info.append("Component: ");
				info.append(row.get("COMPONENT"));
				info.append("\n");
				info.append("Property: ");
				info.append(row.get("PROPERTY"));
				info.append("\n");
				info.append("Time aspect: ");
				info.append(row.get("TIME_ASPCT"));
				info.append("\n");
				info.append("System: ");
				info.append(row.get("SYSTEM"));
				info.append("\n");
				info.append("Scale type: ");
				info.append(row.get("SCALE_TYP"));
				info.append("\n");
				info.append("Method type: ");
				info.append(row.get("METHOD_TYP"));
				info.append("\n");
				info.append("Comments: ");
				info.append(row.get("COMMENTS"));
				info.append("\n");
				info.append("Formula: ");
				info.append(row.get("FORMULA"));
				info.append("\n");
				info.append("Example units: ");
				info.append(row.get("EXAMPLE_UNITS"));
				info.append("\n");
				loincToInfo.put(row.get("LOINC_NUM"), info.toString());
			}
			return loincToInfo;
		}
	}

	// private void process() {
	// StringUtilities.outputWithTime("Indexing terms");
	// UsagiSearchEngine usagiSearchEngine = new UsagiSearchEngine(folder);
	// usagiSearchEngine.createNewMainIndex();
	// CountingSet<String> vocCounts = new CountingSet<String>();
	// for (Row row : new ReadCSVFileWithHeader(termfile)) {
	// TargetConcept concept = new TargetConcept(row);
	// usagiSearchEngine.addConceptToIndex(concept);
	// vocCounts.add(concept.vocabulary);
	// }
	// usagiSearchEngine.close();
	// StringUtilities.outputWithTime("Indexed terms for vocabularies:");
	// vocCounts.printCounts();
	// }

}
