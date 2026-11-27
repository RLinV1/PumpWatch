## ADDED Requirements

### Requirement: Fired rules in flag evidence
The system SHALL include, in the evidence of each flag from the `rules-v1` or `anomaly-v1` scorer, one entry per term that fired: the term name, the feature, the measured value, the threshold of the row that counted, and its points. Other scorers SHALL leave the list empty. Entries SHALL carry aggregate values only, never post text or authors.

#### Scenario: Rules add up to the score
- **WHEN** a `rules-v1` window is scored
- **THEN** the sum of the fired rules' points equals the score before it is kept between 0 and 100

#### Scenario: Highest matching row per term
- **WHEN** `mentionRatio` is 12.4
- **THEN** one "mention surge" entry is listed, with threshold 10 and 35 points

#### Scenario: Trained model
- **WHEN** a flag comes from the ONNX scorer
- **THEN** its evidence has an empty rules list

### Requirement: Old flags stay readable
The system SHALL parse, validate and replay flags recorded before fired rules existed, with an empty rules list, and SHALL NOT change the flag schema version for this field.

#### Scenario: Recorded flag without rules
- **WHEN** a schema version 1 flag without a `rules` field is read
- **THEN** it parses, passes validation and has an empty rules list

#### Scenario: Invalid rule
- **WHEN** a flag carries a rule with a blank name or feature, or a non-finite value, threshold or points
- **THEN** validation reports the problem and the flag is not stored
