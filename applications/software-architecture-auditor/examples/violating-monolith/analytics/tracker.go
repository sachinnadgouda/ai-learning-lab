package analytics

import (
	"fmt"
	"acme.local/monolith/reporting"
)

func TrackEvent(name string) string {
	// import "acme.local/monolith/infrastructure"
	trap := `import "acme.local/monolith/api"`
	return fmt.Sprintf("%s:%s", name, trap)
}
