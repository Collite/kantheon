# Intent and Entity Resolver

This document is a brief for the new Intent and Entity resolver - this should be a "service" on the Platform, in a broad sense; technically, my feeling is that it will be an agent, exposed to other agents through MCP, but also working in isolation.

## The Goal

We are building an agentic platform, that supports agents providing Q&A and analytical services on top of the ERP system. The user can ask questions which are simple ("Which unpaid invoices does Shell have?" or "What is the wage cost evolution on CC 4902?"  or "What is the packaging of 5lt Sheron Rally?"), or complex "Why are our sales of Castrol declining in the private garages?".
Answering these questions requires:
1. understanding the intent of the question, if simple, or saying: this is a complex question
2. identifying the entities mentioned in the question ("Shell" is a customer, "CC 4902" is a cost center, "5lt Sheron Rally" is a product)

The goal is to create a service (agent) that does this - given a question, it returns
1. "parsed" question in the NLP sense (more below)
2. identified entities
3. extracted intent
4. structured function call based on the intent and entities (intent mapped to the function / tool, entities and attributes mapped to parameters)

## Caveats

The whole thing is going to be a) probabilistic and b) iterative

### Probabilistic
Especially the entity detection will not be black&white.
We have a "fuzzy matcher" service on the platform, that can search for strings and fuzzy-match them to known strings. Fuzzy matcher returns a list of candidates, ranked. We shall improve it, but the agent needs to handle the case when fuzzy matcher returns  multiple candidates. This will most probably be the standard case. In the exammple above, "Shell"'s name might be "Shell UK, PLC", and "5lt Sheron Rally" actually is "SHERON Rally 5 liter plastic bottle".

In the same way, the question might be clearly mapped to intent, or might be mapped to an intent with sub-100% confidence.

### Iterative

#### Internal Loop
Given the probabilistic response of intent and entities, the agent should have an internal loop. We might have a word that can mean two different entities (e.g. "order" can mean "purchasing order" when talking about purchases, or "customer order" when talking about customer service or sales). We might have "Give me orders of Shell" mapped to "customer order", because Shell is a customer, and "Give me orders of BASF" mapped to purchasing order, becasue BASF is a supplier.
So there should be a loop that checks the combinations of individual intents and entities and their probabilitites, and tries to find the best interpretation for the whole context.

#### HITL
And sometimes, we will need some hints, clarifications and choices from the end user. For example, if Shell is both customer and supplier, we need to ask, as both entities are possible. The same if we have actually two products "SHERON Rally 5 liters Plastic Bottle" and "SHERON Rally 5 liters Can".

## Parsing

As a separate ask, I would like to get the parsed sentence back, in a structured format:
- tokens
- lemmatization
- POS
- dependency graph relations

The reason is the questions will be in Czech and other central european languages, and we will use also some old-school NLP techniques to handle the ambiguity. The entities should be then resolved and mapped to the tokens.


