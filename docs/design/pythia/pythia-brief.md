# Pythia Brief

This document is a brief for the Pythia autonomous analytical agent. We want to build an agent, that would (semi) autonomously analyze data, present insights, analyze root causes of issues and provide explanations. In addition to that, Pythia would have an extrapolation + forecasting abilities, and simulation abilities.

## Use cases

These are the typical use cases we would want Pythia to solve: 

### Answer (complex) questions

We have the AI Platform project that provides a Platform able to run queries against various data (in various engines). We are developing the simple agents that allow the user to ask simple questions like "What are the invoices of customer Shell?" , backed by pre-defined parametrizable queries. You can see the `docs/platform` folder with the Platform documentation, and `docs/golem` folder with documentation for a template of these simple agents.

Pythia will be able to answer questions that require a) reasoning and / or b) planning. Questions like "Which of the customers that have returned any stock of the Nescafe brand in the last year have decreased their revenue of the Maggi brand during last 2 quarters?" We have the "leaf" predefined queries ("returns by customer", "revenue by month by customer"), but Pythia needs to come up with a plan to run the correct queries in a correct order and answer the complex question.

### Analyze root causes

The question "Why is our revenue YoY lower for the channel Private?" asks for root cause analysis and explanations. The answer should not be a list of customers, as above, but a research-shaped document with reasoning, intermediate results (as charts and tables) and a final explanation.

### Forecasting and Simulations

1. Easy forecasting - extrapolation: "What will our margin look like at the end of the year?"
2. Simulations: if we increase the price by 20%, suffering 5% volume drop, what will be our margin by the end of the year?"

## Model Support

In order to handle queries like those above, we will support Pythia with logical and conceptual metadata model. You can see in the `docs/platform/model` folder the *.ttr files that form the physical and logical (E-R) model; we will add a Conceptual layer over that as well, but for the time being, let's assume Pythia will use the E-R model, with labels specified, that will allow her to extract the nature of the query and the entities (and attributes) the user talks about.

## Tools / Services / Sub-agents

I envisage Pythia as multi-agent framework, or an agent-manager using tools (or other agents) to achieve its goals. 
Namely, the AI Platform already has the "Worker" service that can (should) be utilized to actually run queries (and the Platform defines the languages in which Pythia can ask for a query result)
Additional tools / sub-agents I can envisage are
- Mover: an agent that moves data between different storages; S3, central database (MS SQL), cloud database (Azure SQL), etc
- DataScientist: a specialized Worker with DS / ML libraries for forecasting and ML tasks
- Wrangler: an user-facing agent, supporting the end user with real-time analytics questions
- Secretary: an agent listening to different message channels and talking to the user. Accepting queries via WhatsApp, for example, or running them at 3 A.M. Claw-like.

## Tech Stack

My strong preference for the main agent would be JVM + Kotlin.
The Worker is in Python, and I can assume the DataScientist would be as well, but the logic I would prefer to have JVM + Kotlin based
On the Platform we have the following things available:
- local "S3" - Seaweed
- local PostgreSQL
- local Redis
- NATS JetStream
- LLM Gateway (OpenAI - API, but managing multiple vendors and models)
- metadata service (see the Platform documentation)
- Worker services, in Python with DuckDB and Polars

