## ADDED Requirements

### Requirement: Why a flag fired
The terminal Flags panel and the landing evidence panel SHALL show each flag's fired rules (name, feature, measured value, threshold, signed points) and, when a catalyst was found, the SEC name of each 8-K item. Mock flags SHALL carry mock rules that are labelled mock. The display SHALL NOT give buy or sell advice and SHALL NOT show post text or authors.

#### Scenario: Rules shown
- **WHEN** a flag with fired rules is shown
- **THEN** each rule shows its name, feature, value, threshold and points such as "+35" or "-20"

#### Scenario: Known 8-K item
- **WHEN** a found catalyst lists item 2.02
- **THEN** it reads "2.02 Results of Operations and Financial Condition"

#### Scenario: Unknown 8-K item
- **WHEN** a found catalyst lists an item code that is not in the SEC table
- **THEN** the raw code is shown

#### Scenario: Flag without rules
- **WHEN** a flag has no rules (a trained model, or a flag stored before this change)
- **THEN** the panel says no rule detail is available and still shows the group contributions

#### Scenario: Bad rules rejected
- **WHEN** an API body or stream event carries a rules entry with a non-finite number, a missing name or a raw-content key
- **THEN** the client rejects that data and does not render it
