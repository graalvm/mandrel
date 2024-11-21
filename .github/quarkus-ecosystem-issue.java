/*
 * Copyright 2020,2024 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 21
//DEPS org.kohsuke:github-api:1.326
//DEPS info.picocli:picocli:4.7.6

import org.kohsuke.github.*;
import org.kohsuke.github.GHWorkflowRun.Conclusion;
import org.kohsuke.github.function.InputStreamFunction;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.io.BufferedReader;
import java.io.InputStreamReader;

@Command(name = "report", mixinStandardHelpOptions = true,
		description = "Takes care of updating an issue depending on the status of the build")
class Report implements Runnable {

	@Option(names = "token", description = "Github token to use when calling the Github API", required = true)
	private String token;

	@Option(names = "thisRepo", description = "The repository for which we are reporting the CI status", required = true)
	private String thisRepo;

	@Option(names = "runId", description = "The ID of the Github Action run for  which we are reporting the CI status", required = true)
	private String runId;

	@Option(names = "--dry-run", description = "Whether to actually update the issue or not")
	private boolean dryRun;

	private final HashMap<GHIssue, String> issues = new HashMap<>();

	@Override
	public void run() {
		try {
			final GitHub github = new GitHubBuilder().withOAuthToken(token).build();
			final GHRepository workflowRepository = github.getRepository(thisRepo);
			GHWorkflowRun workflowRun = workflowRepository.getWorkflowRun(Long.parseLong(runId));
			Conclusion status = workflowRun.getConclusion();

			System.out.println(String.format("The CI build had status %s.", status));

			if (status.equals(Conclusion.CANCELLED) || status.equals(Conclusion.SKIPPED)) {
				System.out.println("Job status is `cancelled` or `skipped` - exiting");
				System.exit(0);
			}

			final HashMap<GHIssue, List<GHWorkflowJob>> failedMandrelJobs = new HashMap<>();

			// Get the github issue number and repository from the logs
			// 
			// Unfortunately it's not possible to pass information from a triggering
			// workflow to the triggered workflow (in this case Nightly/Weekly CI to
			// the Github Issue Updater). As a result, to work around this, we parse
			// the logs of the jobs of the workflow that triggered this workflow, in
			// these logs we can find information like the inputs "issue-number",
			// "issue-repo" etc. But we still need to somehow group the jobs
			// corresponding to the detected issue-numbers. To do so, we first parse
			// the logs of the "Set distribution" job, which is the first job of each
			// configuration. This job contains the issue-number and issue-repo inputs
			// which we use to get the github issue and map it to the job name prefix
			// of jobs that are part of the same configuration.
			//
			// We then check the status of the jobs of the triggered workflow, and
			// if any of them failed, we check if the job name starts with one of the
			// job name prefixes we found earlier. If it does, we add it to the list
			// of failed jobs for the corresponding issue.
			//
			// Finally, we process the list of failed jobs for each issue, and if
			// the issue is still open, we add a comment with the list of failed jobs
			// and the filtered logs of the first failed job.
			//
			// Mandrel integration tests are treated specially, as they have a fixed
			// issue repository, we can directly get the issue number from the logs
			// of the job, and we don't need to group the jobs by issue number, since
			// the structure of the workflow is simpler.
			PagedIterable<GHWorkflowJob> listJobs = workflowRun.listJobs();
			listJobs.forEach(job -> {
				// Each configuration starts with the Set distribution job
				if (job.getName().contains("Set distribution")) {
					processLogs(github, job, this::processITJobs, "issue-number", "issue-repo");
				} else if (job.getConclusion().equals(Conclusion.FAILURE) && (job.getName().contains("Q IT") || job.getName().contains("Mandrel build") || job.getName().contains("Quarkus build"))) {
					for (GHIssue issue: issues.keySet()) {
						if (job.getName().startsWith(issues.get(issue))) {
							List<GHWorkflowJob> failedJobsList = failedMandrelJobs.get(issue);
							if (failedJobsList == null) {
								failedJobsList = new java.util.ArrayList<>();
								failedMandrelJobs.put(issue, failedJobsList);
							}
							System.out.println(String.format("Adding job %s to the list of failed jobs for issue %s", job.getName(), issue.getHtmlUrl().toString()));
							failedJobsList.add(job);
						}
					}
				} else if (job.getName().contains("Q Mandrel IT")) {
					String fullContent = getJobsLogs(job, "mandrel-it-issue-number",
							"FAILURE [",
							"Z Error:",
							"  Time elapsed: ",
							"Z [ERROR]   ",
							"Z [ERROR] Failures",
							"Z [ERROR] Tests run:");
					if (!fullContent.isEmpty()) {
						// Get the issue number for mandrel-integration-tests issues
						Matcher mandrelIssueNumberMatcher = Pattern.compile(" mandrel-it-issue-number: (\\d+)").matcher(fullContent);
						if (mandrelIssueNumberMatcher.find()) {
							int mandrelIssueNumber = Integer.parseInt(mandrelIssueNumberMatcher.group(1));
							System.out.println(String.format("Found issue https://github.com/karm/mandrel-integration-tests/issues/%s in logs for job %s", mandrelIssueNumber, job.getName()));
							try {
								GHRepository issueRepository = github.getRepository("karm/mandrel-integration-tests");
								final GHIssue issue = issueRepository.getIssue(mandrelIssueNumber);
								if (issue == null) {
									System.out.println(String.format("Unable to find the issue %s in project %s", mandrelIssueNumber, "karm/mandrel-integration-tests"));
									System.exit(-1);
								} else {
									System.out.println(String.format("Report issue found: %s - %s", issue.getTitle(), issue.getHtmlUrl().toString()));
									System.out.println(String.format("The issue is currently %s", issue.getState().toString()));
									if (job.getConclusion().equals(Conclusion.SUCCESS)) {
										if (isOpen(issue)) {
											String comment = String.format("Build fixed:\n* Link to latest CI run: https://github.com/%s/actions/runs/%s", thisRepo, runId);
											if (!dryRun) {
												// close issue with a comment
												issue.comment(comment);
												issue.close();
											}
											System.out.println(String.format("Comment added on issue %s\n%s\n, the issue has also been closed", issue.getHtmlUrl().toString(), comment));
										} else {
											System.out.println("Nothing to do - the build passed and the issue is already closed");
										}
									} else if (job.getConclusion().equals(Conclusion.FAILURE)) {
										StringBuilder sb = new StringBuilder();
										if (isOpen(issue)) {
											sb.append("The build is still failing!\n\n");
										} else {
											sb.append("Unfortunately, the build failed!\n\n");
											if (!dryRun) {
												issue.reopen();
											}
											System.out.println("The issue has been re-opened");
										}
										sb.append(String.format("Filtered Logs:\n```\n%s\n```\n\n", fullContent.lines().filter(x -> !x.contains("mandrel-it-issue-number")).collect(Collectors.joining("\n"))));
										sb.append(String.format("Link to failing CI run: %s", job.getHtmlUrl()));
										String comment = sb.toString();
										if (!dryRun) {
											issue.comment(comment);
										}
										System.out.println(String.format("\nComment added on issue %s\n\n%s\n", issue.getHtmlUrl().toString(), comment));
									}
								}
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						}
					}
				} else if (job.getName().startsWith("Keep graal/master in sync")) {
					processLogs(github, job, this::processSyncJobs, "issue-number", "issue-repo");
				}
			});

			// Process the failed jobs
			for (GHIssue issue: issues.keySet()) {
				List<GHWorkflowJob> failedJobs = failedMandrelJobs.get(issue);
				if (failedJobs == null || failedJobs.isEmpty()) {
					if (isOpen(issue)) {
						String comment = String.format("Build fixed:\n* Link to latest CI run: https://github.com/%s/actions/runs/%s", thisRepo, runId);
						if (!dryRun) {
							// close issue with a comment
							issue.comment(comment);
							issue.close();
						}
						System.out.println(String.format("Comment added on issue %s\n%s\n, the issue has also been closed", issue.getHtmlUrl().toString(), comment));
					} else {
						System.out.println("Nothing to do - the build passed and the issue is already closed");
					}
				} else {
					StringBuilder sb = new StringBuilder();
					if (isOpen(issue)) {
						sb.append("The build is still failing!\n\n");
					} else {
						sb.append("Unfortunately, the build failed!\n\n");
						if (!dryRun) {
							issue.reopen();
						}
						System.out.println("The issue has been re-opened");
					}
					for (GHWorkflowJob job: failedJobs) {
				 		processFailedJob(sb, job);
					}
					sb.append(String.format("Link to failing CI run: https://github.com/%s/actions/runs/%s", thisRepo, runId));
					String comment = sb.toString();
					if (!dryRun) {
						issue.comment(comment);
					}
					System.out.println(String.format("\nComment added on issue %s\n\n%s\n", issue.getHtmlUrl().toString(), comment));
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	
	private void processLogs(GitHub github, GHWorkflowJob job, BiConsumer<GHIssue, GHWorkflowJob> process, String... filters) {
		String fullContent = getJobsLogs(job, filters);
		if (fullContent.isEmpty()) {
			return;
		}
		// Get the issue number and repository for mandrel issues
		Matcher issueNumberMatcher = Pattern.compile(" issue-number: (\\d+)").matcher(fullContent);
		Matcher issueRepoMatcher = Pattern.compile(" issue-repo: (.*)").matcher(fullContent);
		if (issueNumberMatcher.find() && issueRepoMatcher.find()) {
			int issueNumber = Integer.parseInt(issueNumberMatcher.group(1));
			String issueRepo = issueRepoMatcher.group(1);

			System.out.println(String.format("Found issue https://github.com/%s/issues/%s in logs for job %s", issueRepo, issueNumber, job.getName()));
			try {
				GHRepository issueRepository = github.getRepository(issueRepo);
				GHIssue issue = issueRepository.getIssue(issueNumber);
				process.accept(issue, job);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}

	private void processITJobs(GHIssue issue, GHWorkflowJob job) {
		if (issue == null) {
			System.out.println(String.format("Unable to find the issue %s in project %s", issue.getNumber(), issue.getRepository().getName()));
			System.exit(-1);
		} else {
			System.out.println(String.format("Report issue found: %s - %s", issue.getTitle(), issue.getHtmlUrl().toString()));
			System.out.println(String.format("The issue is currently %s", issue.getState().toString()));
			Object oldIssue = issues.put(issue, job.getName().split(" / ")[0]);
			if (oldIssue != null) {
				System.out.println("WARNING: The issue has already been seen, please check the workflow configuration");
			};
		}
	}

	private void processSyncJobs(GHIssue issue, GHWorkflowJob job) {
		try {
			if (issue == null) {
				System.out.println(String.format("Unable to find the issue %s in project %s", issue.getNumber(), issue.getRepository().getName()));
				System.exit(-1);
			} else {
				System.out.println(String.format("Report issue found: %s - %s", issue.getTitle(), issue.getHtmlUrl().toString()));
				System.out.println(String.format("The issue is currently %s", issue.getState().toString()));
				if (job.getConclusion().equals(Conclusion.SUCCESS)) {
					if (isOpen(issue)) {
						String comment = String.format("Synchronization fixed:\n* Link to latest CI run: https://github.com/%s/actions/runs/%s", thisRepo, runId);
						if (!dryRun) {
							// close issue with a comment
							issue.comment(comment);
							issue.close();
						}
						System.out.println(String.format("Comment added on issue %s\n%s\n, the issue has also been closed", issue.getHtmlUrl().toString(), comment));
					} else {
						System.out.println("Nothing to do - the synchronization passed and the issue is already closed");
					}
				} else if (job.getConclusion().equals(Conclusion.FAILURE)) {
					StringBuilder sb = new StringBuilder();
					if (isOpen(issue)) {
						sb.append("The synchronization is still failing!\n\n");
					} else {
						sb.append("Unfortunately, the synchronization failed!\n\n");
						if (!dryRun) {
							issue.reopen();
						}
						System.out.println("The issue has been re-opened");
					}
					sb.append(String.format("Link to failing CI run: %s", job.getHtmlUrl()));
					String comment = sb.toString();
					if (!dryRun) {
						issue.comment(comment);
					}
					System.out.println(String.format("\nComment added on issue %s\n\n%s\n", issue.getHtmlUrl().toString(), comment));
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void processFailedJob(StringBuilder sb, GHWorkflowJob job) {
		sb.append(String.format("* [%s](%s)\n", job.getName(), job.getHtmlUrl()));
		GHWorkflowJob.Step step = job.getSteps().stream()
				.filter(s -> !(s.getConclusion().equals(Conclusion.SUCCESS) || s.getConclusion().equals(Conclusion.SKIPPED)))
				.findFirst().get();
		sb.append(String.format("  * Step: %s\n", step.getName()));
		String fullContent = getJobsLogs(job, "FAILURE [", "Z Error:");
		if (!fullContent.isEmpty()) {
			sb.append(String.format("    Filtered Logs:\n```\n%s```\n\n", fullContent));
		}
	}

	private String getJobsLogs(GHWorkflowJob job, String... filters) {
		String fullContent = "";
		try {
			System.out.println(String.format("\nGetting logs for job %s", job.getName()));
			fullContent = job.downloadLogs(getLogArchiveInputStreamFunction(filters));
		} catch (IOException e) {
			System.out.println(String.format("Unable to get logs for job %s", job.getName()));
			throw new UncheckedIOException(e);
		}
		return fullContent;
	}

	private static InputStreamFunction<String> getLogArchiveInputStreamFunction(String... filters) {
		return (is) -> {
			StringBuilder stringBuilder = new StringBuilder();
			try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(is))) {
				String line;
				while ((line = bufferedReader.readLine()) != null) {
					if (filters.length == 0) {
						stringBuilder.append(line);
						stringBuilder.append(System.lineSeparator());
					} else {
						for (String filter : filters) {
							if (line.contains(filter)) {
								stringBuilder.append(line);
								stringBuilder.append(System.lineSeparator());
								break;
							}
						}
					}
				}
			}
			return stringBuilder.toString();
		};
	}

	private static boolean isOpen(GHIssue issue) {
		return (issue.getState() == GHIssueState.OPEN);
	}
	
	public static void main(String... args) {
		int exitCode = new CommandLine(new Report()).execute(args);
		System.exit(exitCode);
	}
}
