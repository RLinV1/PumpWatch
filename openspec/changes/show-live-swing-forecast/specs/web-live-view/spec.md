## ADDED Requirements

### Requirement: Next-hour swing in the live terminal
The `/live` terminal SHALL show the selected ticker's next-hour swing (median, upper band and regime) in the score chart header, and each flag's own swing on its flag row, using the same shared component as the landing evidence panel. It SHALL show "No forecast yet." when the swing is null or absent, SHALL label the forecast as mock in mock mode, and SHALL NOT show any direction cue or buy or sell wording.

#### Scenario: Forecast present
- **WHEN** the selected ticker's score carries a swing of median 3.2, upper band 9.5 and regime extreme
- **THEN** the chart header reads "Expected range next hour: about 3.2%, up to 9.5% (high to low). EXTREME for this ticker."

#### Scenario: Flag row shows its own swing
- **WHEN** a flag carries a swing
- **THEN** its row shows that flag's median, upper band and regime, not the current score's

#### Scenario: No forecast
- **WHEN** the swing is null or absent
- **THEN** the surface shows "No forecast yet."

#### Scenario: Mock mode
- **WHEN** the data mode is mock and a swing is shown
- **THEN** it is followed by "Mock forecast, not a model output."

#### Scenario: No direction cue
- **WHEN** a swing is shown on any surface
- **THEN** it reads "Size of move, not direction. Not investment advice." and uses no green, red, arrow or sign

### Requirement: Swing in the flag assistant context
The flag assistant context SHALL include the flag's swing as median, upper band and lowercase regime, or say that no forecast exists yet, and SHALL stay at 600 characters or fewer with the swing kept when drivers are long.

#### Scenario: Swing present
- **WHEN** a flag with a swing is sent to the assistant
- **THEN** the context contains "Next-hour swing: about 3.2%, up to 9.5% (high to low), extreme for this ticker. Size of move, not direction."

#### Scenario: Swing null or absent
- **WHEN** a flag without a swing is sent to the assistant
- **THEN** the context contains "No next-hour swing forecast yet."

#### Scenario: Long drivers
- **WHEN** the flag has 100 drivers
- **THEN** the context is at most 600 characters and still contains the swing
